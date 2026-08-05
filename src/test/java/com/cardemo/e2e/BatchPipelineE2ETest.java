/*
 ******************************************************************
 * Program     : BatchPipelineE2ETest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 end-to-end test (Failsafe tier)
 * Function    : Drives all 300 staged rows through the daily transaction posting job and asserts the
 *               boundary facts that follow from the shipped fixtures themselves.
 * Source      : app/cbl/CBTRN02C.cbl @ 7756d89 - the posting program: mainline :L193-L234, validation
 *               cascade :L370-L422, reject write :L446-L465, category-balance upsert :L467-L528,
 *               account update and sign branch :L545-L560, timestamp :L149 and :L692-L705,
 *               status render :L714-L727
 * Source      : app/cbl/CBTRN01C.cbl @ 7756d89 - the read-only pre-flight folded into the same job
 * Source      : app/cpy/CVTRA06Y.cpy @ 7756d89 - the 350-byte DALYTRAN staging layout
 * Source      : app/cpy/CVTRA05Y.cpy, app/cpy/CVACT01Y.cpy, app/cpy/CVTRA01Y.cpy, app/cpy/CVACT03Y.cpy
 * Source      : app/jcl/POSTTRAN.jcl @ 7756d89 - the job stream; :L36 declares LRECL=430
 * Source      : app/data/ASCII/dailytran.txt @ 7756d89 - the Gate 1 and Gate 4 fixture, 300 x 350
 * Note        : The Gate 1 boundary baseline is Not available; see reportGateOneBaselineAvailability().
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.model.enums.TransactionSource;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * End-to-end boundary verification of the migrated CardDemo batch stream: all 300 rows of
 * {@code app/data/ASCII/dailytran.txt} driven through the daily transaction posting job against a real
 * application context, a real PostgreSQL 16 database and a real object store.
 *
 * <h2>What it does</h2>
 *
 * <p>This class asserts only facts that <strong>follow from the shipped fixtures and the frozen COBOL
 * corpus</strong>. It verifies: the nine-fixture geometry invariant; the position-aware overpunch census of
 * {@code DALYTRAN-AMT} at column 143; the 250-to-50 type, source and sign partition; that the staging
 * column for {@code DALYTRAN-PROC-TS} is {@code CHAR(26)} and blank on every row before posting; that every
 * one of the 300 records is accounted for as either posted or rejected
 * ({@code app/cbl/CBTRN02C.cbl:L202-L219}); that the run ends with the completed-with-rejects exit status
 * keyed on nothing but a non-zero reject count ({@code :L227-L232}); that {@code OVERLIMIT TRANSACTION} is
 * the sole reachable reject code over these fixtures while 100, 101 and 103 are provably unreachable and
 * 109 is never consumed ({@code :L370-L422}, {@code :L556-L558}); that the emitted reject record is exactly
 * 430 bytes decomposing as 350 + 4 + 76 ({@code :L176-L182}, {@code app/jcl/POSTTRAN.jcl:L36}); that a
 * negative amount is added to the cycle <em>debit</em> accumulator with no absolute-value normalisation
 * ({@code :L545-L560}); that an absent category-balance row is an accepted control path rather than an
 * error ({@code :L467-L528}); that the {@code CBTRN01C} pre-flight step runs and writes nothing; and that
 * every generated processing timestamp is the injected fixed clock rendered as
 * {@code yyyy-MM-dd-HH.mm.ss.SS0000} ({@code :L149}, {@code :L692-L705}).
 *
 * <p><strong>What it deliberately does not assert.</strong> No expected reject <em>total</em>. The Gate 1
 * boundary baseline is <strong>Not available</strong> - see
 * {@link #reportGateOneBaselineAvailability()} - and a hand-derived total is not an oracle: two faithful
 * models of the same source disagree over these very fixtures, because
 * {@code app/cbl/CBTRN02C.cbl:L393-L395} re-reads the account per transaction while {@code :L545-L560}
 * mutates its accumulators, so a stateless single-pass count and a stateful count differ. Asserting either
 * would freeze one model as truth, and asserting the implementation's own output would be circular. No
 * baseline file is created and none may be.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Run {@code ./mvnw -B -ntp -Ddependency-check.skip=true -Dit.test=BatchPipelineE2ETest verify}, or the
 * whole gate with {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}.
 *
 * <p><strong>Failsafe collects this tier, not Surefire, and the path is load-bearing.</strong>
 * {@code maven-failsafe-plugin} is bound to {@code src/test/java/com/cardemo/e2e/**} and
 * {@code .../integration/**} at {@code integration-test} and {@code verify} <em>despite</em> the
 * {@code Test} suffix these classes keep; {@code maven-surefire-plugin} is bound to {@code .../unit/**} and
 * explicitly excludes both other trees. A class moved out of the exact {@code com/cardemo/e2e} package
 * matches neither include set, is collected by <strong>neither</strong> plugin, and silently never runs -
 * a green build with both plugins reporting success and no output at all. Confirm a run by finding this
 * class in {@code target/failsafe-reports}, never by a green build alone.
 *
 * <p><strong>A reachable Docker socket is a hard prerequisite.</strong> This tier starts a real PostgreSQL
 * container and a real LocalStack container; there is no in-memory substitute and none is wanted, because
 * an in-memory database would not exercise {@code ddl-auto: validate} against the Flyway-owned schema and
 * an in-memory object store would not prove the 430-byte record geometry at the storage boundary. Where no
 * daemon is reachable the correct report is that the gate is blocked, together with the prerequisite -
 * never an untested pass.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Profile {@code test} is active. It declares no datasource address and no credential; the datasource
 *       is bound from injected container connection details, and the three object-store, queue and
 *       notification endpoints are declared there with <em>no default</em> so a class that registers
 *       nothing fails to refresh rather than reaching outside its own container. This class registers them
 *       from container accessors only, which is why no address literal appears anywhere in this file.</li>
 *   <li>PostgreSQL is pinned by <strong>digest</strong> rather than by the mutable {@code postgres:16} tag,
 *       so the engine cannot change under a source file that claims to pin it; LocalStack is pinned to the
 *       exact tag {@code localstack/localstack:4.14.0}. Both are already resident in the provisioned image
 *       cache, so the suite starts without reaching the network.</li>
 *   <li>Time is pinned to {@code 2022-06-10T19:27:53Z} in UTC and published as a primary
 *       {@link Clock} bean. The value is not arbitrary: columns 279-304 of all 300 fixture rows carry
 *       exactly that one originating timestamp, so the generated processing timestamp agrees with the
 *       input's own notion of the present moment. Nothing here reads the wall clock.</li>
 *   <li>{@code spring.batch.job.enabled} is {@code false}, so the job is launched explicitly through the
 *       injected {@link JobLauncher}. No runner, no scheduler and no {@code @EnableBatchProcessing} is
 *       added - on Spring Boot 3.x that annotation <em>disables</em> Batch auto-configuration and would
 *       remove the launcher this class depends on.</li>
 *   <li>The three Flyway migrations own the 11-table schema, the three alternate indexes and the seed data,
 *       including all 300 staged rows. The {@code BATCH_*} metadata tables come from the framework script
 *       via {@code spring.batch.jdbc.initialize-schema} and never from a fourth migration.</li>
 *   <li>The token signing key is generated in memory for the run. It is not a committed secret and no
 *       credential, password or hash appears in this file.</li>
 * </ul>
 *
 * <h2>Numeric and privacy policy, stated once</h2>
 *
 * <p>Every monetary value is a {@link BigDecimal} at the precision its picture clause dictates -
 * {@code NUMERIC(11,2)} for {@code DALYTRAN-AMT} and {@code TRAN-CAT-BAL}, {@code NUMERIC(12,2)} for the
 * account money fields. <strong>No {@code float} and no {@code double} appears in any financial assertion
 * here.</strong> Comparison is by {@code compareTo} and never by {@code equals}, because a
 * {@code NUMERIC(n,2)} round trip returns scale 2 while {@code equals} is scale-sensitive. <strong>No
 * rounding mode is applied anywhere in this class, because it performs no division:</strong> the overpunch
 * decoder scales by moving the decimal point, which is exact, and every other operation is addition or
 * subtraction. Where rounding does arise in the production tier it is {@code RoundingMode.HALF_EVEN}.
 *
 * <p>The one place a double is examined is a Micrometer meter, whose API is double-precision by definition;
 * it is checked for presence only, never for a monetary equality. Assertion messages name the COBOL locator
 * and the offending value, but never personal data. The guarantee is structural rather than editorial:
 * <strong>this class never reads the sensitive spans at all</strong> - it declares no field span for
 * {@code CUST-SSN} (columns 280-288 of {@code custdata.txt}), for the telephone numbers (250-279) or for the
 * date of birth (309-318), so no run and no failure can route one into a message or a log line. Card
 * numbers are read, because the cross-reference contract of {@code app/cbl/CBTRN02C.cbl:L380-L392} is
 * expressed in them; they are the synthetic sample values shipped in {@code app/data/ASCII/**}, no message
 * authored here interpolates one, and they are exercised only as a width and as set membership.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Container startup fails, or the whole class errors in setup</dt>
 *   <dd>No container runtime is reachable. Start the daemon and re-run; report the gate as blocked rather
 *       than as a pass.</dd>
 *
 *   <dt>{@code Could not resolve dependencies ... org.testcontainers:localstack:2.0.3}</dt>
 *   <dd>The Testcontainers 2.x line renamed every module artefact and the bare {@code postgresql},
 *       {@code localstack} and {@code junit-jupiter} ids do not exist at 2.0.3. Both halves of the remedy
 *       are required: pin the managed version by overriding the version property - never by importing a
 *       second bill of materials, since Spring Boot already imports one at a 1.x version - and use only the
 *       four prefixed coordinates. Overriding without renaming resolves artefacts that do not exist;
 *       renaming without overriding resolves the wrong version. The same rename moved the packages, so
 *       import {@link PostgreSQLContainer} from {@code org.testcontainers.postgresql} and
 *       {@link LocalStackContainer} from {@code org.testcontainers.localstack}, never the legacy
 *       {@code org.testcontainers.containers} equivalents, and note that the 2.x PostgreSQL container type
 *       is not generic.</dd>
 *
 *   <dt>{@code warnings found and -Werror specified}</dt>
 *   <dd>Compilation escalates every warning to an error under {@code -Xlint:all -Werror} with
 *       {@code failOnWarning}, so one raw type, one unchecked cast or one deprecated call fails the whole
 *       build. An unused import is not among them, because {@code javac} 25 publishes no {@code unused}
 *       lint key, so that prohibition is review-enforced instead.</dd>
 *
 *   <dt>A resource-not-found failure naming a fixture</dt>
 *   <dd>The fixture is {@code dailytran.txt}. The mainframe DD name and dataset are {@code DALYTRAN}, but
 *       the ASCII fixture spells the word in full, so {@code dalytran.txt} resolves to nothing and yields a
 *       null far from the cause. {@link #readFixture(FixtureGeometry)} therefore fails loudly and names the
 *       resource rather than returning empty.</dd>
 *
 *   <dt>{@code JobInstanceAlreadyCompleteException}, or duplicate-key failures on {@code tran_id}</dt>
 *   <dd>The posting job was launched more than once against one database. It declares no incrementer on
 *       purpose, and posting commits per chunk, so a second run over the same staged rows must collide
 *       rather than be silently absorbed. This class launches exactly once, in setup, and every test
 *       asserts against the captured result.</dd>
 *
 *   <dt>Startup fails inside Hibernate schema validation</dt>
 *   <dd>{@code ddl-auto: validate} compares JDBC type codes, so a column whose type differs from the mapped
 *       one aborts the refresh. The fix belongs upstream in {@code V1__create_schema.sql} or in the entity.
 *       Do not widen a column to silence it and do not patch this test.</dd>
 *
 *   <dt>An amount assertion fails on values that look identical</dt>
 *   <dd>A {@code BigDecimal} comparison used {@code equals} rather than {@code compareTo}.
 *       {@code NUMERIC(n,2)} always reads back at scale 2, and {@code equals} is scale-sensitive. Every
 *       monetary assertion here compares by {@code compareTo}.</dd>
 * </dl>
 *
 * <h2>Evidence register, classified by severity</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - relocating or renaming this class, which removes it from both test
 *       plugins with no error. Remediation: keep the file at
 *       {@code src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java} with package
 *       {@code com.cardemo.e2e}.</li>
 *   <li><strong>Blocker</strong> - the Testcontainers 2.0.3 coordinate and package rename. Remediation: the
 *       two-part remedy above.</li>
 *   <li><strong>High</strong> - modelling {@code dalytran_proc_ts} as a timestamp rather than
 *       {@code CHAR(26)}. All 300 rows carry 26 blanks, which no timestamp type can hold. Remediation is
 *       the authored {@code CHAR(26)} column, asserted by
 *       {@link #stagedInputPreservesRecordGeometryAndBlankProcessingTimestamp()}.</li>
 *   <li><strong>High</strong> - normalising the sign of a negative amount, which would silently corrupt the
 *       over-limit formula of {@code :L403-L405} on the following cycle. Remediation is the authored
 *       accumulator split, asserted by {@link #negativeAmountsAccumulateOnTheCycleDebitSide()}.</li>
 *   <li><strong>Medium</strong> - treating a not-found category-balance read as an error, which would abend
 *       the run at {@code :L481}. Remediation is the accepted {@code '23'} control path, asserted by
 *       {@link #absentCategoryBalanceRowIsCreatedRatherThanTreatedAsAnError()}.</li>
 *   <li><strong>Medium</strong> - a closed source resolver that rejects {@code OPERATOR}. Remediation is
 *       the non-throwing resolver over a raw {@code CHAR(10)} column, asserted by
 *       {@link #bothFixtureSourceLiteralsSurviveTheLoadPath()}.</li>
 *   <li><strong>Low</strong> - the {@code ACCT-EXPIRAION-DATE} misspelling of
 *       {@code app/cpy/CVACT01Y.cpy:L11}, preserved deliberately rather than corrected.</li>
 *   <li><strong>Not available</strong> - the Gate 1 boundary baseline, reported verbatim by
 *       {@link #reportGateOneBaselineAvailability()} together with what would be needed to produce it.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(BatchPipelineE2ETest.FixedClockConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("the daily transaction posting pipeline over all 300 shipped fixture rows")
public class BatchPipelineE2ETest {

    /**
     * Creates the single test instance.
     *
     * <p>Declared explicitly rather than left implicit so that the class carries no undocumented member, the
     * standard the {@code doclint} gate holds main sources to. JUnit instantiates this once, because the
     * lifecycle is {@code PER_CLASS}; every field is then assigned by {@link #setUp()} and never reassigned.
     */
    public BatchPipelineE2ETest() {
        // No state is established here: the captured snapshot is built by setUp(), which needs the injected
        // collaborators and therefore cannot run until after construction and dependency injection.
    }

    /** Diagnostic sink for this class, used for the evidence report rather than for assertions. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchPipelineE2ETest.class);

    /**
     * PostgreSQL 16 by digest rather than by the mutable {@code postgres:16} tag, so the engine this gate
     * measures cannot change while the source still claims to pin it.
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20";

    /** Deterministic encoding and collation for the container's initial database. */
    private static final String POSTGRES_INIT_ARGUMENTS = "--encoding=UTF8 --locale=C";

    /** The emulator, pinned to the exact tag the compose stack and the sibling tiers name. */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:4.14.0";

    /** Bounded startup budget, so an unreachable runtime fails with a diagnosis rather than hanging. */
    private static final long CONTAINER_STARTUP_TIMEOUT_SECONDS = 300L;

    /**
     * The single instant this run observes, matching the one distinct {@code DALYTRAN-ORIG-TS} value that
     * columns 279-304 carry on all 300 fixture rows.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** Byte length of the in-memory token signing key generated for this run. */
    private static final int SIGNING_KEY_BYTES = 48;

    /** The FIFO queue that replaces {@code DEFINE TDQUEUE(JOBS)}; required because the strategy is FAIL. */
    private static final String REPORT_QUEUE_NAME = "carddemo-report-jobs.fifo";

    /** Logical object-store and notification resource names, created on the emulator before refresh. */
    private static final String INPUT_BUCKET = "carddemo-batch-input";

    /** Destination of the reject generation and the transaction mirror. */
    private static final String OUTPUT_BUCKET = "carddemo-batch-output";

    /** Destination of the statement outputs; created so the context can bind its property. */
    private static final String STATEMENTS_BUCKET = "carddemo-statements";

    /** The notification topic that replaces operator notification. */
    private static final String NOTIFICATION_TOPIC = "carddemo-notifications";

    /** The in-memory signing key, generated once so the context can bind its defaultless property. */
    private static final String EPHEMERAL_SIGNING_KEY = generateEphemeralSigningKey();

    // ====================================================================================================
    // The fixture contract. Every figure below was measured from the fixture itself, not transcribed from
    // prose, and the universal invariant is bytes == rows x (width + 1) because each record carries exactly
    // one trailing line feed.
    // ====================================================================================================

    /**
     * The nine ASCII fixtures of {@code app/data/ASCII}, copied into {@code src/test/resources} and consumed
     * here by classpath resource name only.
     *
     * <p>{@code cardxref.txt} is the one short record: {@code XREF-CARD-NUM X(16)} plus
     * {@code XREF-CUST-ID 9(09)} plus {@code XREF-ACCT-ID 9(11)} is 36 populated bytes in a 50-byte VSAM
     * slot, and the {@code FILLER X(14)} of {@code app/cpy/CVACT03Y.cpy} is absent from every row. Padding it
     * to 50 would make the file 2,550 bytes rather than the measured 1,850.
     *
     * <p>{@code discgrp.txt} carries 51 rows, not 50: three 17-row blocks under {@code A000000000},
     * {@code DEFAULT} and {@code ZEROAPR}.
     */
    private static final List<FixtureGeometry> FIXTURE_CONTRACT = List.of(
            new FixtureGeometry("trantype.txt", 427, 7, 60),
            new FixtureGeometry("trancatg.txt", 1_098, 18, 60),
            new FixtureGeometry("discgrp.txt", 2_601, 51, 50),
            new FixtureGeometry("custdata.txt", 25_050, 50, 500),
            new FixtureGeometry("acctdata.txt", 15_050, 50, 300),
            new FixtureGeometry("carddata.txt", 7_550, 50, 150),
            new FixtureGeometry("cardxref.txt", 1_850, 50, 36),
            new FixtureGeometry("tcatbal.txt", 2_550, 50, 50),
            new FixtureGeometry("dailytran.txt", 105_300, 300, 350));

    /**
     * The Gate 1 and Gate 4 fixture. Spelled in full: the mainframe dataset is {@code DALYTRAN} but the
     * ASCII fixture is {@code dailytran.txt}, and the abbreviated spelling resolves to nothing.
     */
    private static final FixtureGeometry DAILY_TRANSACTION_FIXTURE = FIXTURE_CONTRACT.stream()
            .filter(geometry -> "dailytran.txt".equals(geometry.resourceName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                    "The daily transaction fixture is absent from FIXTURE_CONTRACT; it is the Gate 1 and "
                            + "Gate 4 input and the contract cannot be expressed without it"));

    /** The account master fixture, source of the credit limits and expiry dates the cascade compares. */
    private static final FixtureGeometry ACCOUNT_FIXTURE = FIXTURE_CONTRACT.stream()
            .filter(geometry -> "acctdata.txt".equals(geometry.resourceName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                    "The account fixture is absent from FIXTURE_CONTRACT; reject codes 101 and 103 cannot be "
                            + "shown unreachable without it"));

    /** The cross-reference fixture, source of the card-to-account mapping reject code 100 depends on. */
    private static final FixtureGeometry CROSS_REFERENCE_FIXTURE = FIXTURE_CONTRACT.stream()
            .filter(geometry -> "cardxref.txt".equals(geometry.resourceName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                    "The cross-reference fixture is absent from FIXTURE_CONTRACT; reject code 100 cannot be "
                            + "shown unreachable without it"));

    // ----------------------------------------------------------------------------------------------------
    // app/cpy/CVTRA06Y.cpy - the 350-byte DALYTRAN layout, as 1-based inclusive column spans. The spans sum
    // to exactly 350 and are asserted to do so, because a mis-transcribed offset is the one error in this
    // file that would produce confidently wrong assertions rather than a failure.
    // ----------------------------------------------------------------------------------------------------

    /** {@code DALYTRAN-ID X(16)}. */
    private static final FieldSpan DALYTRAN_ID = new FieldSpan(1, 16);

    /** {@code DALYTRAN-TYPE-CD X(02)}. */
    private static final FieldSpan DALYTRAN_TYPE_CD = new FieldSpan(17, 18);

    /** {@code DALYTRAN-CAT-CD 9(04)}. */
    private static final FieldSpan DALYTRAN_CAT_CD = new FieldSpan(19, 22);

    /** {@code DALYTRAN-SOURCE X(10)}. */
    private static final FieldSpan DALYTRAN_SOURCE = new FieldSpan(23, 32);

    /** {@code DALYTRAN-DESC X(100)}. */
    private static final FieldSpan DALYTRAN_DESC = new FieldSpan(33, 132);

    /** {@code DALYTRAN-AMT S9(09)V99}, eleven characters whose last byte carries the overpunched sign. */
    private static final FieldSpan DALYTRAN_AMT = new FieldSpan(133, 143);

    /** {@code DALYTRAN-MERCHANT-ID 9(09)}. */
    private static final FieldSpan DALYTRAN_MERCHANT_ID = new FieldSpan(144, 152);

    /** {@code DALYTRAN-MERCHANT-NAME X(50)}. */
    private static final FieldSpan DALYTRAN_MERCHANT_NAME = new FieldSpan(153, 202);

    /** {@code DALYTRAN-MERCHANT-CITY X(50)}. */
    private static final FieldSpan DALYTRAN_MERCHANT_CITY = new FieldSpan(203, 252);

    /** {@code DALYTRAN-MERCHANT-ZIP X(10)}, mixed five-digit and plus-four forms across the 300 rows. */
    private static final FieldSpan DALYTRAN_MERCHANT_ZIP = new FieldSpan(253, 262);

    /** {@code DALYTRAN-CARD-NUM X(16)}. */
    private static final FieldSpan DALYTRAN_CARD_NUM = new FieldSpan(263, 278);

    /** {@code DALYTRAN-ORIG-TS X(26)}. */
    private static final FieldSpan DALYTRAN_ORIG_TS = new FieldSpan(279, 304);

    /** {@code DALYTRAN-PROC-TS X(26)}, blank on every shipped row and generated only at post time. */
    private static final FieldSpan DALYTRAN_PROC_TS = new FieldSpan(305, 330);

    /** {@code FILLER X(20)}, which together with the blank processing timestamp gives a 46-space tail. */
    private static final FieldSpan DALYTRAN_FILLER = new FieldSpan(331, 350);

    /** Every span of the staging layout in declaration order, used to prove the spans tile the record. */
    private static final List<FieldSpan> DAILY_TRANSACTION_LAYOUT = List.of(
            DALYTRAN_ID, DALYTRAN_TYPE_CD, DALYTRAN_CAT_CD, DALYTRAN_SOURCE, DALYTRAN_DESC, DALYTRAN_AMT,
            DALYTRAN_MERCHANT_ID, DALYTRAN_MERCHANT_NAME, DALYTRAN_MERCHANT_CITY, DALYTRAN_MERCHANT_ZIP,
            DALYTRAN_CARD_NUM, DALYTRAN_ORIG_TS, DALYTRAN_PROC_TS, DALYTRAN_FILLER);

    // ----------------------------------------------------------------------------------------------------
    // app/cpy/CVACT01Y.cpy and app/cpy/CVACT03Y.cpy - only the spans this class actually reads.
    // ----------------------------------------------------------------------------------------------------

    /** {@code ACCT-ID 9(11)}. */
    private static final FieldSpan ACCT_ID = new FieldSpan(1, 11);

    /** {@code ACCT-CREDIT-LIMIT S9(10)V99}, twelve characters with an overpunched sign. */
    private static final FieldSpan ACCT_CREDIT_LIMIT = new FieldSpan(25, 36);

    /**
     * {@code ACCT-EXPIRAION-DATE X(10)} - the misspelling is the field's real name at
     * {@code app/cpy/CVACT01Y.cpy:L11} and is preserved rather than corrected.
     */
    private static final FieldSpan ACCT_EXPIRAION_DATE = new FieldSpan(59, 68);

    /** {@code ACCT-CURR-CYC-CREDIT S9(10)V99}. */
    private static final FieldSpan ACCT_CURR_CYC_CREDIT = new FieldSpan(79, 90);

    /** {@code ACCT-CURR-CYC-DEBIT S9(10)V99}. */
    private static final FieldSpan ACCT_CURR_CYC_DEBIT = new FieldSpan(91, 102);

    /** {@code XREF-CARD-NUM X(16)}. */
    private static final FieldSpan XREF_CARD_NUM = new FieldSpan(1, 16);

    /** {@code XREF-ACCT-ID 9(11)}. */
    private static final FieldSpan XREF_ACCT_ID = new FieldSpan(26, 36);

    // ----------------------------------------------------------------------------------------------------
    // Zoned-decimal trailing-sign overpunch. Decoding is position-aware, driven by the spans above and never
    // by a global text substitution: A through R occur legitimately inside DALYTRAN-DESC,
    // DALYTRAN-MERCHANT-NAME, DALYTRAN-MERCHANT-CITY and ACCT-ADDR-ZIP, which is literally A000000000 on
    // all 50 account rows.
    // ----------------------------------------------------------------------------------------------------

    /** Overpunch characters denoting a non-negative value, mapped to the digit each contributes. */
    private static final Map<Character, Character> POSITIVE_OVERPUNCH = Map.of(
            '{', '0', 'A', '1', 'B', '2', 'C', '3', 'D', '4',
            'E', '5', 'F', '6', 'G', '7', 'H', '8', 'I', '9');

    /** Overpunch characters denoting a negative value, mapped to the digit each contributes. */
    private static final Map<Character, Character> NEGATIVE_OVERPUNCH = Map.of(
            '}', '0', 'J', '1', 'K', '2', 'L', '3', 'M', '4',
            'N', '5', 'O', '6', 'P', '7', 'Q', '8', 'R', '9');

    /** Implied decimal places of every {@code V99} picture clause in the fixtures this class decodes. */
    private static final int IMPLIED_DECIMAL_PLACES = 2;

    /**
     * The measured census of the sign byte at column 143 of {@code dailytran.txt}, character by character.
     *
     * <p>It sums to 250 non-negative and 50 negative rows, and those 50 are the <strong>only</strong>
     * coverage of the cycle-debit branch of {@code app/cbl/CBTRN02C.cbl:L551} in the entire fixture set, so
     * a change to this distribution silently removes the only proof that the sign branch is exercised.
     */
    private static final Map<Character, Integer> EXPECTED_SIGN_CENSUS = Map.ofEntries(
            Map.entry('{', 25), Map.entry('A', 28), Map.entry('B', 29), Map.entry('C', 30),
            Map.entry('D', 29), Map.entry('E', 23), Map.entry('F', 21), Map.entry('G', 24),
            Map.entry('H', 17), Map.entry('I', 24),
            Map.entry('J', 3), Map.entry('K', 5), Map.entry('L', 5), Map.entry('M', 6),
            Map.entry('N', 2), Map.entry('O', 4), Map.entry('P', 7), Map.entry('Q', 4),
            Map.entry('R', 8), Map.entry('}', 6));

    /** Rows whose amount is non-negative, and therefore accumulate on the cycle-credit side. */
    private static final int EXPECTED_NON_NEGATIVE_ROWS = 250;

    /** Rows whose amount is negative, and therefore accumulate on the cycle-debit side. */
    private static final int EXPECTED_NEGATIVE_ROWS = 50;

    /** {@code DALYTRAN-TYPE-CD} of the 250 point-of-sale rows. */
    private static final String POS_TYPE_CODE = "01";

    /** {@code DALYTRAN-TYPE-CD} of the 50 operator rows. */
    private static final String OPERATOR_TYPE_CODE = "03";

    /** {@code DALYTRAN-SOURCE} of the 250 point-of-sale rows, at its full {@code X(10)} width. */
    private static final String POS_SOURCE_LITERAL = "POS TERM  ";

    /**
     * {@code DALYTRAN-SOURCE} of the 50 operator rows, at its full {@code X(10)} width.
     *
     * <p>{@link TransactionSource} carries no constant for this literal, and that is correct rather than a
     * gap: the resolver returns an empty {@link java.util.Optional} instead of throwing, and the staging
     * column is a raw {@code CHAR(10)}, so these 50 rows load unchanged. A closed, throwing resolver on the
     * load path would break the fixture load outright.
     */
    private static final String OPERATOR_SOURCE_LITERAL = "OPERATOR  ";

    /** The single {@code DALYTRAN-CAT-CD} value present on all 300 rows. */
    private static final String EXPECTED_CATEGORY_CODE = "0001";

    /** The single {@code DALYTRAN-MERCHANT-ID} value present on all 300 rows. */
    private static final String EXPECTED_MERCHANT_ID = "800000000";

    /** The single {@code DALYTRAN-ORIG-TS} value present on all 300 rows, in pass-through form. */
    private static final String EXPECTED_ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** Distinct card numbers across the 300 staged rows, so roughly six transactions per card. */
    private static final int EXPECTED_DISTINCT_CARD_NUMBERS = 50;

    /** Width of every timestamp field in the corpus, all of them {@code CHAR(26)} rather than temporal. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Characters of {@code DALYTRAN-ORIG-TS} the expiry comparison of {@code :L414} examines. */
    private static final int EXPIRY_COMPARISON_LENGTH = 10;

    /** The blank {@code DALYTRAN-PROC-TS} every shipped row carries before posting. */
    private static final String BLANK_TIMESTAMP = " ".repeat(TIMESTAMP_WIDTH);

    /**
     * The date, time and hundredths portion of the batch timestamp flavour, before its four literal zeros.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L149} documents the shape as
     * {@code EEEE-MM-DD-UU.MM.SS.HH0000} and {@code :L701} appends {@code MOVE '0000' TO DB2-REST}, so the
     * separator between the day and the hour is a <strong>hyphen</strong> and the sub-second portion is
     * hundredths followed by four zeros - never nanoseconds, and never the space that the online flavour
     * carries at position 11.
     */
    private static final DateTimeFormatter BATCH_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS", Locale.ROOT);

    /** The four literal zeros {@code :L701} moves into {@code DB2-REST}. */
    private static final String BATCH_TIMESTAMP_TRAILING_ZEROS = "0000";

    // ----------------------------------------------------------------------------------------------------
    // The four published metric names and their tag keys, stated as literals rather than imported from the
    // class that declares them. That is deliberate on two counts.
    //
    // First, these names are a PUBLISHED CONTRACT, not an internal detail:
    // observability/grafana/dashboards/carddemo-dashboard.json and observability/prometheus.yml consume them
    // by name, so a rename silently breaks a dashboard. Importing the declaring constant would make the
    // assertion vacuous - it would rename in step with the metric and could never fail - whereas a literal
    // fails loudly here, which is exactly the signal wanted.
    //
    // Second, com.cardemo.observability.MetricsConfig is not among this file's declared dependencies, so it
    // is not an importable collaborator for this test. Each value below was read from its declaration site
    // and is cited to it rather than guessed.
    // ----------------------------------------------------------------------------------------------------

    /** Counter replacing {@code DISPLAY 'TRANSACTIONS PROCESSED :'} at {@code app/cbl/CBTRN02C.cbl:L227}. */
    private static final String METRIC_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /** Counter replacing {@code DISPLAY 'TRANSACTIONS REJECTED  :'} at {@code app/cbl/CBTRN02C.cbl:L228}. */
    private static final String METRIC_RECORDS_REJECTED = "carddemo.batch.records.rejected";

    /** Counter over sign-on outcomes, from {@code app/cbl/COSGN00C.cbl}. */
    private static final String METRIC_AUTHENTICATION_ATTEMPTS = "carddemo.auth.attempts";

    /** Counter over posted amounts, split by the sign branch of {@code app/cbl/CBTRN02C.cbl:L548-L552}. */
    private static final String METRIC_TRANSACTION_AMOUNT_TOTAL = "carddemo.transaction.amount.total";

    /** The one tag key on the rejected-records counter, bounded to the five reject codes. */
    private static final String TAG_REJECT_CODE = "reject.code";

    /** The one tag key on the amount counter, carrying the sign the accumulator cannot. */
    private static final String TAG_SIGN = "sign";

    /** Tag value for the non-negative branch of {@code app/cbl/CBTRN02C.cbl:L549}. */
    private static final String SIGN_CREDIT = "credit";

    /** Tag value for the negative branch of {@code app/cbl/CBTRN02C.cbl:L551}. */
    private static final String SIGN_DEBIT = "debit";

    // ====================================================================================================
    // Container lifecycle. The two fields are static because one lifecycle must span the class, and final so
    // they are immutable after start. They carry no @Container annotation on purpose: a container the
    // extension manages is stopped in afterAll, while Spring caches the test context on a key that does not
    // include the container, so a later class could inherit a pool addressing a removed container. They are
    // started once by the static initialiser below and left to the resource reaper at JVM exit.
    //
    // These two references are the only static mutable-by-construction state in the class. Every value the
    // tests assert against is an instance field assigned once in setUp and never written again.
    // ====================================================================================================

    /**
     * The relational substrate. {@code @ServiceConnection} contributes the JDBC connection details as a
     * bean, at a higher precedence than any datasource property, which is what keeps every address and every
     * credential out of this file.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withEnv("POSTGRES_INITDB_ARGS", POSTGRES_INIT_ARGUMENTS);

    /** The emulator substrate, exposing only the three services this pipeline touches. */
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns");

    static {
        try {
            Startables.deepStart(POSTGRES, LOCALSTACK)
                    .get(CONTAINER_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException expired) {
            throw new IllegalStateException(
                    "The batch end-to-end containers did not become ready within "
                            + CONTAINER_STARTUP_TIMEOUT_SECONDS + " seconds.", expired);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while starting the batch end-to-end containers.", interrupted);
        } catch (final ExecutionException | RuntimeException startupFailure) {
            throw new IllegalStateException(
                    "The batch end-to-end containers could not start. A reachable container runtime is a "
                            + "prerequisite of this gate: the 430-byte reject geometry is measured at the "
                            + "object-storage boundary and the schema is validated against the "
                            + "Flyway-owned PostgreSQL 16 database, so no in-memory substitute is valid and "
                            + "skipping the class would create an untested pass.", startupFailure);
        }
    }

    /**
     * Supplies every endpoint, logical resource name and throwaway credential from the running containers,
     * and provisions the emulator resources before the application context refreshes.
     *
     * <p>The {@code test} profile declares the three AWS endpoints with no default precisely so that a class
     * which registers nothing fails to refresh rather than inheriting an ambient address, and the queue
     * strategy is {@code FAIL}, so the FIFO queue must exist before the context starts. Both obligations are
     * discharged here.
     *
     * @param registry Spring's dynamic property registry, which contributes at a higher precedence than any
     *     profile file; must not be {@code null}
     */
    @DynamicPropertySource
    static void registerContainerProperties(final DynamicPropertyRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");

        final URI endpoint = LOCALSTACK.getEndpoint();
        final String region = LOCALSTACK.getRegion();
        final String accessKey = LOCALSTACK.getAccessKey();
        final String secretKey = LOCALSTACK.getSecretKey();

        provisionEmulatorResources(endpoint, region, accessKey, secretKey);

        registry.add("spring.cloud.aws.region.static", () -> region);
        registry.add("spring.cloud.aws.credentials.access-key", () -> accessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", () -> secretKey);
        registry.add("spring.cloud.aws.s3.endpoint", endpoint::toString);
        registry.add("spring.cloud.aws.sqs.endpoint", endpoint::toString);
        registry.add("spring.cloud.aws.sns.endpoint", endpoint::toString);
        registry.add("carddemo.aws.s3.batch-input-bucket", () -> INPUT_BUCKET);
        registry.add("carddemo.aws.s3.batch-output-bucket", () -> OUTPUT_BUCKET);
        registry.add("carddemo.aws.s3.statements-bucket", () -> STATEMENTS_BUCKET);
        registry.add("carddemo.aws.sqs.report-queue", () -> REPORT_QUEUE_NAME);
        registry.add("carddemo.aws.sns.notification-topic", () -> NOTIFICATION_TOPIC);
        registry.add("carddemo.security.jwt.signing-key", () -> EPHEMERAL_SIGNING_KEY);
    }

    /**
     * Creates the three buckets, enables versioning on the output bucket, creates the FIFO queue and creates
     * the notification topic, mirroring {@code localstack-init/init-aws.sh}.
     *
     * <p>Versioning on the output bucket is what lets a relative generation reference such as
     * {@code DALYREJS(+1)} become an object version rather than an overwrite.
     *
     * @param endpoint the emulator endpoint, taken from the container accessor
     * @param region the emulator region, taken from the container accessor
     * @param accessKey the emulator's throwaway access key, taken from the container accessor
     * @param secretKey the emulator's throwaway secret key, taken from the container accessor
     */
    private static void provisionEmulatorResources(final URI endpoint, final String region,
            final String accessKey, final String secretKey) {

        final StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .forcePathStyle(Boolean.TRUE)
                .build()) {

            s3.createBucket(CreateBucketRequest.builder().bucket(INPUT_BUCKET).build());
            s3.createBucket(CreateBucketRequest.builder().bucket(OUTPUT_BUCKET).build());
            s3.createBucket(CreateBucketRequest.builder().bucket(STATEMENTS_BUCKET).build());
            s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(OUTPUT_BUCKET)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
        }

        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build()) {

            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(REPORT_QUEUE_NAME)
                    .attributesWithStrings(Map.of(
                            QueueAttributeName.FIFO_QUEUE.toString(), "true",
                            QueueAttributeName.CONTENT_BASED_DEDUPLICATION.toString(), "false"))
                    .build());
        }

        try (SnsClient sns = SnsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build()) {

            sns.createTopic(CreateTopicRequest.builder().name(NOTIFICATION_TOPIC).build());
        }
    }

    /**
     * Generates the run's token signing key in memory.
     *
     * <p>{@code carddemo.security.jwt.signing-key} has no default anywhere, by design, so the context cannot
     * refresh without a value. Generating one per run satisfies that without committing a credential: Rule 1
     * clause D names tests explicitly, so no literal key, password or hash appears in this file.
     *
     * @return a URL-safe base64 key of {@value #SIGNING_KEY_BYTES} random bytes, never {@code null}
     */
    private static String generateEphemeralSigningKey() {
        final byte[] material = new byte[SIGNING_KEY_BYTES];
        new SecureRandom().nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * Publishes the pinned clock the production tier consumes.
     *
     * <p>Registered under a name of its own and marked {@link Primary} because
     * {@code spring.main.allow-bean-definition-overriding} is {@code false}: a name collision must fail the
     * refresh rather than silently shadow a production bean.
     */
    @TestConfiguration
    static class FixedClockConfiguration {

        /**
         * Creates the configuration.
         *
         * <p>Declared explicitly for the same reason as the enclosing class's constructor: no member is left
         * undocumented. Spring instantiates it while processing {@code @Import}.
         */
        FixedClockConfiguration() {
            // No state: the single bean below is a pure function of the enclosing class's pinned instant.
        }

        /**
         * The single time source this run observes.
         *
         * <p>{@code TRAN-PROC-TS} is generated at post time rather than carried on the input - columns
         * 305-330 of all 300 fixture rows are blank - so the processing timestamp comes entirely from this
         * bean. Pinning it to the fixture's own originating instant makes the generated value agree with the
         * input's notion of the present moment and keeps it inside the report's inclusive date window.
         *
         * @return a fixed UTC clock at {@code 2022-06-10T19:27:53Z}, never {@code null}
         */
        @Bean("batchPipelineE2EFixedClock")
        @Primary
        Clock batchPipelineE2EFixedClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

    // ====================================================================================================
    // Small immutable value carriers. Records rather than classes because each is a pure data tuple, and
    // nested rather than separate files because this package is not permitted a further source file.
    // ====================================================================================================

    /**
     * The measured contract of one fixture: its classpath resource name and the three figures that must hold
     * together as {@code byteLength == rowCount * (recordWidth + 1)}.
     *
     * @param resourceName the classpath resource name, spelled exactly as the file is
     * @param expectedByteLength the total byte length including one line feed per record
     * @param expectedRowCount the number of records
     * @param expectedRecordWidth the fixed record width excluding the line feed
     */
    private record FixtureGeometry(String resourceName, int expectedByteLength, int expectedRowCount,
                                   int expectedRecordWidth) {
    }

    /**
     * A 1-based inclusive column span, which is how every COBOL picture clause position is expressed.
     *
     * @param start the first column, 1-based and inclusive
     * @param end the last column, inclusive
     */
    private record FieldSpan(int start, int end) {

        /**
         * Extracts this span from a fixed-width record.
         *
         * @param record the record to slice; must be at least {@link #end} characters wide
         * @return the field text, retaining every space exactly as stored
         */
        String of(final String record) {
            return record.substring(start - 1, end);
        }

        /**
         * Reports the declared width of this span.
         *
         * @return the number of characters the span covers
         */
        int width() {
            return end - start + 1;
        }
    }

    /**
     * One fixture, read exactly once.
     *
     * @param geometry the declared contract this fixture is measured against
     * @param byteLength the measured byte length of the whole resource
     * @param rows the records, in file order, with every space retained
     * @param carriageReturnCount the number of {@code 0x0D} bytes, which must be zero
     * @param endsWithLineFeed whether the final byte is {@code 0x0A}
     * @param highestCodePoint the greatest byte value observed, which must stay inside 7-bit ASCII
     */
    private record Fixture(FixtureGeometry geometry, int byteLength, List<String> rows,
                           int carriageReturnCount, boolean endsWithLineFeed, int highestCodePoint) {
    }

    /**
     * One staged input record, decoded position-aware from the spans of {@code app/cpy/CVTRA06Y.cpy}.
     *
     * @param transactionId {@code DALYTRAN-ID}
     * @param typeCode {@code DALYTRAN-TYPE-CD}
     * @param categoryCode {@code DALYTRAN-CAT-CD}
     * @param source {@code DALYTRAN-SOURCE} at full width, padding retained
     * @param amount {@code DALYTRAN-AMT} decoded through the overpunch table
     * @param signCharacter the raw sign byte at column 143
     * @param merchantId {@code DALYTRAN-MERCHANT-ID}
     * @param merchantZip {@code DALYTRAN-MERCHANT-ZIP} at full width
     * @param cardNumber {@code DALYTRAN-CARD-NUM}
     * @param originTimestamp {@code DALYTRAN-ORIG-TS}
     * @param processingTimestamp {@code DALYTRAN-PROC-TS}, blank on every shipped row
     */
    private record StagedRecord(String transactionId, String typeCode, String categoryCode, String source,
                                BigDecimal amount, char signCharacter, String merchantId, String merchantZip,
                                String cardNumber, String originTimestamp, String processingTimestamp) {
    }

    /**
     * The two cycle accumulators of one account, captured either side of the posting run.
     *
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT}
     * @param cycleDebit {@code ACCT-CURR-CYC-DEBIT}
     * @param currentBalance {@code ACCT-CURR-BAL}
     */
    private record AccountCycleState(BigDecimal cycleCredit, BigDecimal cycleDebit,
                                     BigDecimal currentBalance) {
    }

    // ====================================================================================================
    // Injected collaborators. Field injection is used because JUnit owns the instance lifecycle, so a
    // constructor is not available for it.
    // ====================================================================================================

    /** The job under test, injected by its authored bean name rather than by type. */
    @Autowired
    @Qualifier(DailyTransactionPostingJob.JOB_BEAN_NAME)
    private Job dailyTransactionPostingJob;

    /** The launcher; {@code spring.batch.job.enabled} is false, so every run is explicit. */
    @Autowired
    private JobLauncher jobLauncher;

    /** The staged input door, used to observe the 300 rows before posting consumes them. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /** The posted-output door; the table is empty until the run produces it. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** The account master, observed either side of the run for the cycle-accumulator split. */
    @Autowired
    private AccountRepository accountRepository;

    /** The category-balance store, observed either side of the run for the upsert branches. */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** The cross-reference, which decides reject codes 100 and 101. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** The object store, used to read back the reject generation and measure its record geometry. */
    @Autowired
    private S3Operations objectStorage;

    /** The meter registry, used to observe the four named batch counters. */
    @Autowired
    private MeterRegistry meterRegistry;

    /** The pinned clock, the only time source this class consults. */
    @Autowired
    private Clock clock;

    /** The destination bucket, bound from the same property the writers bind. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String outputBucket;

    // ====================================================================================================
    // Captured state. Every field below is assigned exactly once by setUp and never written again, so the
    // tests read a consistent snapshot and none can influence another. Rule 1 clause B forbids global
    // mutable state; these are per-instance and effectively final after setup.
    // ====================================================================================================

    /** The nine fixtures, read once and keyed by resource name. */
    private Map<String, Fixture> fixtures;

    /** The 300 decoded staging records, in file order. */
    private List<StagedRecord> stagedRecords;

    /** Card number to account identifier, from {@code cardxref.txt}. */
    private Map<String, String> crossReferenceByCard;

    /** Account identifier to credit limit, from {@code acctdata.txt}. */
    private Map<String, BigDecimal> creditLimitByAccount;

    /** Account identifier to {@code ACCT-EXPIRAION-DATE}, from {@code acctdata.txt}. */
    private Map<String, String> expiryDateByAccount;

    /** Staged rows as the database held them immediately before the run. */
    private List<DailyTransaction> stagedRowsBeforePosting;

    /** Cycle accumulators per account immediately before the run. */
    private Map<Long, AccountCycleState> accountStateBeforePosting;

    /** Category-balance keys present immediately before the run, rendered as {@code acct|type|cat}. */
    private Set<String> categoryBalanceKeysBeforePosting;

    /** Rows in the posted-transaction table immediately before the run. */
    private long transactionCountBeforePosting;

    /** The single posting run. */
    private JobExecution postingExecution;

    /** {@code WS-TRANSACTION-COUNT} as the run reported it. */
    private long processedCount;

    /** {@code WS-REJECT-COUNT} as the run reported it. */
    private long rejectedCount;

    /** Every transaction the run committed. */
    private List<Transaction> postedTransactions;

    /** Cycle accumulators per account immediately after the run. */
    private Map<Long, AccountCycleState> accountStateAfterPosting;

    /** Category-balance keys present immediately after the run. */
    private Set<String> categoryBalanceKeysAfterPosting;

    /** The reject generation object key the run published, or {@code null} when it rejected nothing. */
    private String rejectObjectKey;

    /** The reject generation content, read back byte-for-byte, or {@code null} when no object exists. */
    private String rejectObjectContent;

    /**
     * Reads every fixture once, captures the pre-posting state, launches the posting job exactly once and
     * captures the post-posting state.
     *
     * <p><strong>Why one launch.</strong> {@link DailyTransactionPostingJob} declares no incrementer and
     * posting commits per chunk, so a second run over the same staged rows would collide on {@code tran_id}
     * primary keys - deliberately, because a repeated post must surface rather than be absorbed. One launch
     * in setup, with every test asserting against the captured snapshot, is therefore the only correct shape,
     * and it also honours the single-pass requirement: the 300 rows are read once, not once per assertion.
     *
     * <p><strong>Side effects.</strong> Commits the posting run's writes to the container database and
     * creates at most one reject generation object in the container's output bucket. Both are disposed with
     * the containers at JVM exit; nothing outside the containers is touched and no fixture is written.
     *
     * @throws IllegalStateException if a fixture is missing, if the staged input is not the expected 300
     *     rows, or if the job cannot be launched - each reported with the cause preserved, because a setup
     *     failure that presented as a pass would be the worst outcome available here
     */
    @BeforeAll
    void setUp() {
        this.fixtures = FIXTURE_CONTRACT.stream().collect(Collectors.toUnmodifiableMap(
                FixtureGeometry::resourceName, BatchPipelineE2ETest::readFixture));

        this.stagedRecords = this.fixtures.get(DAILY_TRANSACTION_FIXTURE.resourceName()).rows().stream()
                .map(BatchPipelineE2ETest::decodeStagedRecord)
                .toList();

        this.crossReferenceByCard = this.fixtures.get(CROSS_REFERENCE_FIXTURE.resourceName()).rows().stream()
                .collect(Collectors.toUnmodifiableMap(XREF_CARD_NUM::of, XREF_ACCT_ID::of));

        final List<String> accountRows = this.fixtures.get(ACCOUNT_FIXTURE.resourceName()).rows();
        this.creditLimitByAccount = accountRows.stream().collect(Collectors.toUnmodifiableMap(
                ACCT_ID::of, row -> decodeOverpunchedAmount(ACCT_CREDIT_LIMIT.of(row))));
        this.expiryDateByAccount = accountRows.stream()
                .collect(Collectors.toUnmodifiableMap(ACCT_ID::of, ACCT_EXPIRAION_DATE::of));

        this.stagedRowsBeforePosting = List.copyOf(this.dailyTransactionRepository.findAll());
        if (this.stagedRowsBeforePosting.size() != DAILY_TRANSACTION_FIXTURE.expectedRowCount()) {
            throw new IllegalStateException(
                    "V3__seed_data.sql must stage exactly " + DAILY_TRANSACTION_FIXTURE.expectedRowCount()
                            + " rows from app/data/ASCII/dailytran.txt into daily_transaction, but "
                            + this.stagedRowsBeforePosting.size() + " were present. Nothing downstream of "
                            + "this can be asserted, so the run stops here rather than reporting a "
                            + "misleading pass.");
        }

        this.accountStateBeforePosting = captureAccountState();
        this.categoryBalanceKeysBeforePosting = captureCategoryBalanceKeys();
        this.transactionCountBeforePosting = this.transactionRepository.count();

        this.postingExecution = launchPostingJob();

        final ExecutionContext jobContext = this.postingExecution.getExecutionContext();
        this.processedCount = readCounter(jobContext, DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY);
        this.rejectedCount = readCounter(jobContext, DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY);

        this.postedTransactions = List.copyOf(this.transactionRepository.findAll());
        this.accountStateAfterPosting = captureAccountState();
        this.categoryBalanceKeysAfterPosting = captureCategoryBalanceKeys();

        this.rejectObjectKey = resolveRejectObjectKey(jobContext);
        this.rejectObjectContent = this.rejectObjectKey == null ? null : readRejectGeneration();

        LOG.info(reportGateOneBaselineAvailability());
    }

    // ====================================================================================================
    // Public evidence surface. One method, documented per Rule 1 clause B, and deliberately assertion-free.
    // ====================================================================================================

    /**
     * Reports the availability of the Gate 1 boundary-parity baseline.
     *
     * <p><strong>Purpose.</strong> Rule 1 clause F requires that missing information be stated as
     * {@code Not available} together with what would be needed, rather than filled with an invention. This
     * method is that statement, in code rather than in prose alone, so the evidence travels with the run.
     *
     * <p><strong>Inputs.</strong> None; the answer is a property of the repository at commit
     * {@code 7756d89}, not of any run. An exhaustive search for expected, baseline, golden, system-output,
     * reject, report, statement and HTML captures returned only three dataset <em>definition</em> job-control
     * members - {@code app/jcl/DALYREJS.jcl}, {@code app/jcl/TRANREPT.jcl} and
     * {@code app/proc/TRANREPT.prc} - and zero captured data, and a byte-size sweep for 430-, 133-, 860- and
     * 266-byte artefacts returned nothing.
     *
     * <p><strong>Outputs.</strong> The report text, beginning with the literal {@code Not available} and
     * carrying the needed-evidence sentence verbatim. That sentence, reproduced here verbatim so the
     * statement stands in the documentation as well as in the returned text, is: <em>a captured DALYREJS
     * 430-byte reject dataset plus the resulting TRANSACT / ACCTDATA / TCATBALF images from a real POSTTRAN
     * execution at a known input state.</em>
     *
     * <p><strong>Side effects.</strong> None. It asserts nothing, writes no file and creates no baseline;
     * the caller decides what to do with the text. Creating a baseline file here would be worse than useless,
     * because a baseline derived from this implementation's own output is circular and could only ever
     * confirm the implementation against itself.
     *
     * <p><strong>Error modes.</strong> None; it is a pure function with no failure path.
     *
     * @return the Gate 1 availability report, never {@code null} and never blank
     */
    public String reportGateOneBaselineAvailability() {
        return "Gate 1 boundary-parity baseline: Not available. What is needed: "
                + "a captured DALYREJS 430-byte reject dataset plus the resulting TRANSACT / ACCTDATA / "
                + "TCATBALF images from a real POSTTRAN execution at a known input state. "
                + "Until those exist, no expected reject total is asserted: two faithful models of "
                + "app/cbl/CBTRN02C.cbl disagree over these fixtures, because :L393-L395 re-reads the "
                + "account per transaction while :L545-L560 mutates its accumulators, so any hand-derived "
                + "total is model-sensitive rather than an oracle.";
    }

    // ====================================================================================================
    // Fixture reading and position-aware decoding. Static and pure: none of these touches instance state.
    // ====================================================================================================

    /**
     * Reads one fixture from the classpath, measuring it rather than trusting it.
     *
     * <p>Bytes are decoded through {@link StandardCharsets#ISO_8859_1} so that one byte maps to exactly one
     * character and a measured character width is a measured <em>byte</em> width. Decoding as ASCII would
     * silently substitute a replacement character for any byte above 127 and so destroy the very evidence the
     * pure-ASCII assertion rests on; the highest byte value is therefore measured from the raw bytes instead.
     *
     * @param geometry the declared contract, whose {@code resourceName} is resolved on the classpath
     * @return the fixture with its measurements attached, never {@code null}
     * @throws IllegalStateException if the resource is absent or unreadable, naming it explicitly - the
     *     fixture is {@code dailytran.txt} and the abbreviated {@code dalytran.txt} resolves to nothing
     */
    private static Fixture readFixture(final FixtureGeometry geometry) {
        final byte[] raw;
        try (InputStream stream = new ClassPathResource(geometry.resourceName()).getInputStream()) {
            raw = stream.readAllBytes();
        } catch (final IOException unreadable) {
            throw new IllegalStateException(
                    "Fixture '" + geometry.resourceName() + "' could not be read from the classpath. It is "
                            + "expected at src/test/resources/" + geometry.resourceName() + ". Note the "
                            + "spelling: the mainframe dataset is DALYTRAN but the ASCII fixture spells the "
                            + "word in full, so 'dalytran.txt' resolves to nothing.", unreadable);
        }
        if (raw.length == 0) {
            throw new IllegalStateException("Fixture '" + geometry.resourceName()
                    + "' resolved but is empty; an empty fixture cannot evidence any boundary fact.");
        }

        int carriageReturns = 0;
        int highestCodePoint = 0;
        for (final byte value : raw) {
            final int unsigned = value & 0xFF;
            if (unsigned == '\r') {
                carriageReturns++;
            }
            highestCodePoint = Math.max(highestCodePoint, unsigned);
        }

        final String text = new String(raw, StandardCharsets.ISO_8859_1);
        final List<String> rows = new ArrayList<>();
        int cursor = 0;
        while (cursor < text.length()) {
            final int lineFeed = text.indexOf('\n', cursor);
            if (lineFeed < 0) {
                rows.add(text.substring(cursor));
                break;
            }
            rows.add(text.substring(cursor, lineFeed));
            cursor = lineFeed + 1;
        }

        return new Fixture(geometry, raw.length, List.copyOf(rows), carriageReturns,
                raw[raw.length - 1] == (byte) '\n', highestCodePoint);
    }

    /**
     * Decodes one 350-byte staging record into its declared fields.
     *
     * <p>Every field is taken from its own column span, so the amount's overpunched sign byte is read at
     * column 143 and nowhere else. A global text substitution over the record would be wrong rather than
     * merely crude: {@code A} through {@code R} occur legitimately inside {@code DALYTRAN-DESC},
     * {@code DALYTRAN-MERCHANT-NAME} and {@code DALYTRAN-MERCHANT-CITY}.
     *
     * @param record one fixed-width record, exactly 350 characters wide
     * @return the decoded record, never {@code null}
     * @throws IllegalStateException if the record is not the declared width, since every downstream span
     *     would then be reading the wrong bytes
     */
    private static StagedRecord decodeStagedRecord(final String record) {
        if (record.length() != DAILY_TRANSACTION_FIXTURE.expectedRecordWidth()) {
            throw new IllegalStateException("A staging record must be exactly "
                    + DAILY_TRANSACTION_FIXTURE.expectedRecordWidth() + " characters per app/cpy/CVTRA06Y.cpy "
                    + "but one measured " + record.length() + "; every column span would otherwise decode the "
                    + "wrong bytes.");
        }
        final String amountField = DALYTRAN_AMT.of(record);
        return new StagedRecord(
                DALYTRAN_ID.of(record),
                DALYTRAN_TYPE_CD.of(record),
                DALYTRAN_CAT_CD.of(record),
                DALYTRAN_SOURCE.of(record),
                decodeOverpunchedAmount(amountField),
                amountField.charAt(amountField.length() - 1),
                DALYTRAN_MERCHANT_ID.of(record),
                DALYTRAN_MERCHANT_ZIP.of(record),
                DALYTRAN_CARD_NUM.of(record),
                DALYTRAN_ORIG_TS.of(record),
                DALYTRAN_PROC_TS.of(record));
    }

    /**
     * Decodes a zoned-decimal field whose trailing byte carries the sign as an overpunch.
     *
     * <p>The table is {@code {} to plus zero, {@code A} through {@code I} to plus one through plus nine,
     * {@code }} to minus zero and {@code J} through {@code R} to minus one through minus nine. The result is
     * scaled by the two implied decimal places every {@code V99} clause in these fixtures declares.
     *
     * @param field the raw field text, whose last character is the overpunched sign byte
     * @return the decoded value at scale {@value #IMPLIED_DECIMAL_PLACES}, never {@code null}
     * @throws IllegalStateException if the trailing byte is not a recognised overpunch character, which
     *     means either the span is misaligned or the fixture has changed
     */
    private static BigDecimal decodeOverpunchedAmount(final String field) {
        final char sign = field.charAt(field.length() - 1);
        final String leadingDigits = field.substring(0, field.length() - 1);
        final Character positiveDigit = POSITIVE_OVERPUNCH.get(sign);
        final Character negativeDigit = NEGATIVE_OVERPUNCH.get(sign);
        if (positiveDigit == null && negativeDigit == null) {
            throw new IllegalStateException("The trailing byte of a zoned-decimal field is not a recognised "
                    + "overpunch character. Expected one of {A-I for a non-negative value or }J-R for a "
                    + "negative one; the span is either misaligned or the fixture has changed. Field width "
                    + "was " + field.length() + " characters.");
        }
        final boolean negative = negativeDigit != null;
        final char finalDigit = negative ? negativeDigit.charValue() : positiveDigit.charValue();
        final BigDecimal magnitude = new BigDecimal(leadingDigits + finalDigit)
                .movePointLeft(IMPLIED_DECIMAL_PLACES);
        return negative ? magnitude.negate() : magnitude;
    }

    // ====================================================================================================
    // State capture and job launch.
    // ====================================================================================================

    /**
     * Captures the cycle accumulators and balance of every account, keyed by identifier.
     *
     * @return an immutable snapshot, never {@code null}
     */
    private Map<Long, AccountCycleState> captureAccountState() {
        return this.accountRepository.findAll().stream().collect(Collectors.toUnmodifiableMap(
                Account::getAccountId,
                account -> new AccountCycleState(account.getCurrentCycleCredit(),
                        account.getCurrentCycleDebit(), account.getCurrentBalance())));
    }

    /**
     * Captures the composite keys currently present in the category-balance store.
     *
     * @return an immutable set of {@code account|type|category} renderings, never {@code null}
     */
    private Set<String> captureCategoryBalanceKeys() {
        return this.transactionCategoryBalanceRepository.findAll().stream()
                .map(BatchPipelineE2ETest::renderCategoryBalanceKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Renders a category-balance composite key into a comparable string.
     *
     * <p>The three components are those of {@code app/cpy/CVTRA01Y.cpy}: the account identifier, the
     * two-character type code and the four-digit category code, whose third member is {@code TRANCAT-CD}.
     *
     * @param balance the row whose key is to be rendered; must not be {@code null}
     * @return the rendering, never {@code null}
     */
    private static String renderCategoryBalanceKey(final TransactionCategoryBalance balance) {
        return balance.getId().getAccountId() + "|" + balance.getId().getTypeCd().strip() + "|"
                + balance.getId().getCatCd();
    }

    /**
     * Launches the posting job exactly once, carrying a single identifying parameter.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:L23} carries no {@code PARM=}, so the job requires no business
     * parameter and none is invented. The one parameter supplied is a run discriminator, which is the
     * sanctioned way to name a job instance; the job's own validator accepts unrecognised names for exactly
     * that reason.
     *
     * @return the completed execution, never {@code null}
     * @throws IllegalStateException if the launch itself is refused, distinguishing a launch failure - where
     *     no exit status and no counter exists to assert on - from a business failure, with the cause
     *     preserved
     */
    private JobExecution launchPostingJob() {
        final JobParameters parameters = new JobParametersBuilder()
                .addString("carddemo.e2e.runId", "batch-pipeline-e2e")
                .toJobParameters();
        try {
            return this.jobLauncher.run(this.dailyTransactionPostingJob, parameters);
        } catch (final JobExecutionException launchRefused) {
            throw new IllegalStateException(
                    "The posting job could not be launched, so no exit status and no counter exists to "
                            + "assert on. This is a launch failure rather than a business failure. A "
                            + "second launch against one database would also fail here, by design: the job "
                            + "declares no incrementer and posting commits per chunk, so a repeated run must "
                            + "collide rather than be absorbed.", launchRefused);
        }
    }

    /**
     * Reads a counter from an execution context, treating an absent entry as zero.
     *
     * <p>Absent is a legitimate state - a run whose input was empty never writes one - so it is handled
     * explicitly rather than allowed to throw.
     *
     * @param context the context to read; must not be {@code null}
     * @param key the entry name
     * @return the value, or zero when the entry is absent
     */
    private static long readCounter(final ExecutionContext context, final String key) {
        return context.containsKey(key) ? context.getLong(key) : 0L;
    }

    /**
     * Resolves the reject generation's object key from the job execution context through the writer's
     * <em>indexed</em> job-level protocol.
     *
     * <p><strong>Why the indexed entry rather than the direct one.</strong>
     * {@code RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY} is a <em>step</em>-context entry, and it is written
     * by the writer's {@code close()}, because a key naming an object the store has not yet accepted would
     * be a lie. Spring Batch calls {@code StepExecutionListener.afterStep} <em>before</em> it closes the
     * item streams, so the job's promotion pass has already run by the time that entry exists and cannot
     * carry it upward. The writer therefore publishes the key to the job context itself, as a one-entry
     * ordered list guarded by a count - and that list, not the promoted direct key, is the supported
     * job-level read. Binding to the direct key here would look correct and always resolve to nothing.
     *
     * <p>An absent count is a legitimate state rather than an error: a run that rejects nothing creates no
     * generation at all, and it is reported as {@code null} rather than as a missing object.
     *
     * @param jobContext the finished run's job execution context; must not be {@code null}
     * @return the single generation object key, or {@code null} when the run created no generation
     */
    private static String resolveRejectObjectKey(final ExecutionContext jobContext) {
        if (!jobContext.containsKey(RejectWriter.REJECT_OBJECT_KEYS_COUNT_ENTRY)) {
            return null;
        }
        if (jobContext.getLong(RejectWriter.REJECT_OBJECT_KEYS_COUNT_ENTRY) <= 0L) {
            return null;
        }
        return jobContext.getString(RejectWriter.rejectObjectKeysIndexEntry(0));
    }

    /**
     * Reads the reject generation object back byte-for-byte.
     *
     * <p>Decoded through {@link StandardCharsets#ISO_8859_1} for the same reason the fixtures are: the
     * assertion is about byte counts, and a one-to-one byte-to-character decoding is what makes a character
     * count a byte count.
     *
     * @return the object content, never {@code null}
     * @throws IllegalStateException if the published key names an object that cannot be read, with the cause
     *     preserved
     */
    private String readRejectGeneration() {
        try (InputStream stored = this.objectStorage.download(this.outputBucket, this.rejectObjectKey)
                .getInputStream()) {
            return new String(stored.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new IllegalStateException(
                    "The reject generation published by the run could not be read back from the output "
                            + "bucket, so the 430-byte record geometry of app/jcl/POSTTRAN.jcl:L36 cannot be "
                            + "measured. The object key is published under "
                            + RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY + ".", unreadable);
        }
    }

    // ====================================================================================================
    // The fixtures themselves. These run first in reading order because every later assertion rests on the
    // input being exactly what it is measured to be here.
    // ====================================================================================================

    /**
     * All nine fixtures satisfy the universal invariant: {@code bytes == rows x (width + 1)}, zero carriage
     * returns, pure 7-bit ASCII, and a final line feed.
     */
    @Test
    @DisplayName("app/data/ASCII/**: all nine fixtures are fixed-width, LF-terminated, CR-free 7-bit ASCII")
    void allNineFixturesSatisfyTheUniversalGeometryInvariant() {
        assertThat(this.fixtures)
                .as("all nine ASCII fixtures of app/data/ASCII must be present on the test classpath")
                .hasSize(FIXTURE_CONTRACT.size());

        for (final FixtureGeometry geometry : FIXTURE_CONTRACT) {
            final Fixture fixture = this.fixtures.get(geometry.resourceName());

            assertThat(fixture.byteLength())
                    .as("%s must measure %d bytes, being %d records of %d characters plus one line feed each",
                            geometry.resourceName(), geometry.expectedByteLength(),
                            geometry.expectedRowCount(), geometry.expectedRecordWidth())
                    .isEqualTo(geometry.expectedByteLength());

            assertThat(fixture.rows())
                    .as("%s must hold exactly %d records", geometry.resourceName(),
                            geometry.expectedRowCount())
                    .hasSize(geometry.expectedRowCount());

            assertThat(fixture.byteLength())
                    .as("%s must satisfy bytes == rows x (width + 1); a mismatch means the record width or "
                            + "the line terminator is not what the layout declares", geometry.resourceName())
                    .isEqualTo(geometry.expectedRowCount() * (geometry.expectedRecordWidth() + 1));

            assertThat(fixture.rows())
                    .as("every record of %s must be exactly %d characters wide; a whitespace cleanup would "
                            + "destroy the fixed-width geometry the parity contract rests on",
                            geometry.resourceName(), geometry.expectedRecordWidth())
                    .allMatch(row -> row.length() == geometry.expectedRecordWidth());

            assertThat(fixture.carriageReturnCount())
                    .as("%s must carry no carriage return; a CRLF fixture would widen every record by one "
                            + "byte and shift every column span", geometry.resourceName())
                    .isZero();

            assertThat(fixture.highestCodePoint())
                    .as("%s must be pure 7-bit ASCII; the EBCDIC images under app/data/EBCDIC are reference "
                            + "only and are never parsed by the build", geometry.resourceName())
                    .isLessThan(128);

            assertThat(fixture.endsWithLineFeed())
                    .as("%s must end with a line feed, which is what makes the byte count an exact multiple "
                            + "of width + 1", geometry.resourceName())
                    .isTrue();
        }
    }

    /**
     * The fourteen column spans of {@code app/cpy/CVTRA06Y.cpy} tile the 350-byte record exactly, with no
     * gap and no overlap. This guards every other assertion in the class: a mis-transcribed offset would
     * otherwise decode the wrong bytes confidently rather than fail.
     */
    @Test
    @DisplayName("app/cpy/CVTRA06Y.cpy: the fourteen DALYTRAN spans tile the 350-byte record exactly")
    void stagingLayoutSpansTileTheRecordWithoutGapOrOverlap() {
        int expectedStart = 1;
        int total = 0;
        for (final FieldSpan span : DAILY_TRANSACTION_LAYOUT) {
            assertThat(span.start())
                    .as("the DALYTRAN spans must be contiguous; the span at column %d does not begin where "
                            + "its predecessor ended, so app/cpy/CVTRA06Y.cpy has been mis-transcribed",
                            span.start())
                    .isEqualTo(expectedStart);
            expectedStart = span.end() + 1;
            total += span.width();
        }

        assertThat(total)
                .as("the DALYTRAN spans must sum to the declared RECLN of 350 in app/cpy/CVTRA06Y.cpy")
                .isEqualTo(DAILY_TRANSACTION_FIXTURE.expectedRecordWidth());

        assertThat(DALYTRAN_AMT.width())
                .as("DALYTRAN-AMT is PIC S9(09)V99, so eleven characters: nine integer digits, two decimals "
                        + "and the sign carried as an overpunch on the last byte")
                .isEqualTo(11);

        assertThat(DALYTRAN_ORIG_TS.width())
                .as("DALYTRAN-ORIG-TS is PIC X(26)")
                .isEqualTo(TIMESTAMP_WIDTH);

        assertThat(DALYTRAN_PROC_TS.width())
                .as("DALYTRAN-PROC-TS is PIC X(26), which is why the staging column is CHAR(26) and never a "
                        + "temporal type: no timestamp type can hold the 26 blanks every shipped row carries")
                .isEqualTo(TIMESTAMP_WIDTH);
    }

    /**
     * The measured field census of {@code dailytran.txt}: the 250-to-50 type, source and sign partition, the
     * single category code, merchant identifier and originating timestamp, the 300 distinct identifiers, the
     * 50 distinct card numbers, the mixed postal-code formats and the blank processing timestamp.
     */
    @Test
    @DisplayName("app/data/ASCII/dailytran.txt: the 300 rows carry the measured field census")
    void dailyTransactionFixtureCarriesTheMeasuredFieldCensus() {
        assertThat(this.stagedRecords)
                .as("the Gate 1 and Gate 4 fixture holds 300 records")
                .hasSize(DAILY_TRANSACTION_FIXTURE.expectedRowCount());

        assertThat(this.stagedRecords).extracting(StagedRecord::categoryCode)
                .as("DALYTRAN-CAT-CD is 0001 on all 300 rows, which is why every posting touches the same "
                        + "category component of the CVTRA01Y composite key")
                .containsOnly(EXPECTED_CATEGORY_CODE);

        assertThat(this.stagedRecords).extracting(StagedRecord::merchantId)
                .as("DALYTRAN-MERCHANT-ID is a single distinct value across all 300 rows")
                .containsOnly(EXPECTED_MERCHANT_ID);

        assertThat(this.stagedRecords).extracting(StagedRecord::originTimestamp)
                .as("DALYTRAN-ORIG-TS is exactly one distinct value, and it is the instant the run's clock is "
                        + "pinned to; note position 11 is a space, the online flavour, and it is passed "
                        + "through rather than reformatted")
                .containsOnly(EXPECTED_ORIGIN_TIMESTAMP);

        assertThat(this.stagedRecords).extracting(StagedRecord::transactionId)
                .as("all 300 DALYTRAN-ID values are distinct, so the run cannot mask a duplicate post")
                .doesNotHaveDuplicates()
                .hasSize(DAILY_TRANSACTION_FIXTURE.expectedRowCount());

        assertThat(this.stagedRecords.stream().map(StagedRecord::cardNumber).distinct().count())
                .as("the 300 rows spread over 50 distinct card numbers, roughly six transactions per card")
                .isEqualTo(EXPECTED_DISTINCT_CARD_NUMBERS);

        assertThat(this.stagedRecords.stream().map(StagedRecord::processingTimestamp).distinct().toList())
                .as("DALYTRAN-PROC-TS is 26 blanks on every one of the 300 rows: the processing timestamp is "
                        + "generated at post time by app/cbl/CBTRN02C.cbl:L437-L438, never carried on input")
                .containsExactly(BLANK_TIMESTAMP);

        final Map<String, Long> byTypeCode = this.stagedRecords.stream().collect(
                Collectors.groupingBy(StagedRecord::typeCode, TreeMap::new, Collectors.counting()));
        assertThat(byTypeCode)
                .as("the fixture partitions perfectly into 250 type-01 rows and 50 type-03 rows")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        POS_TYPE_CODE, Long.valueOf(EXPECTED_NON_NEGATIVE_ROWS),
                        OPERATOR_TYPE_CODE, Long.valueOf(EXPECTED_NEGATIVE_ROWS)));

        assertThat(this.stagedRecords.stream()
                .filter(record -> POS_TYPE_CODE.equals(record.typeCode()))
                .allMatch(record -> POS_SOURCE_LITERAL.equals(record.source())
                        && record.amount().signum() >= 0))
                .as("every type-01 row pairs the point-of-sale source with a non-negative amount")
                .isTrue();

        assertThat(this.stagedRecords.stream()
                .filter(record -> OPERATOR_TYPE_CODE.equals(record.typeCode()))
                .allMatch(record -> OPERATOR_SOURCE_LITERAL.equals(record.source())
                        && record.amount().signum() < 0))
                .as("every type-03 row pairs the operator source with a negative amount; these 50 rows are "
                        + "the only coverage of the cycle-debit branch at app/cbl/CBTRN02C.cbl:L551 in the "
                        + "entire fixture set")
                .isTrue();

        assertThat(this.stagedRecords.stream().map(StagedRecord::merchantZip).distinct().count())
                .as("DALYTRAN-MERCHANT-ZIP holds 300 distinct values in mixed five-digit and plus-four "
                        + "formats, so no five-digit-only postal-code validator may exist on the load path")
                .isEqualTo(DAILY_TRANSACTION_FIXTURE.expectedRowCount());

        assertThat(this.stagedRecords.stream().map(StagedRecord::merchantZip)
                .anyMatch(zip -> zip.strip().length() == 5))
                .as("at least one postal code is the bare five-digit form")
                .isTrue();

        assertThat(this.stagedRecords.stream().map(StagedRecord::merchantZip)
                .anyMatch(zip -> zip.strip().contains("-")))
                .as("at least one postal code is the plus-four form, which is what makes a five-digit-only "
                        + "validator a real hazard rather than a theoretical one")
                .isTrue();
    }

    /**
     * The position-aware overpunch census of {@code DALYTRAN-AMT} at column 143, character by character,
     * together with the decoded amount range and the absence of any zero amount.
     */
    @Test
    @DisplayName("app/cpy/CVTRA06Y.cpy: the sign byte at column 143 censuses 250 non-negative to 50 negative")
    void overpunchCensusAtColumnOneHundredFortyThreeMatchesTheMeasuredDistribution() {
        final Map<Character, Integer> census = this.stagedRecords.stream().collect(Collectors.groupingBy(
                StagedRecord::signCharacter, TreeMap::new,
                Collectors.reducing(0, record -> Integer.valueOf(1), Integer::sum)));

        assertThat(census)
                .as("the overpunch distribution at column 143 is a measured property of the fixture; a change "
                        + "here silently alters which posting branches the gate exercises")
                .containsExactlyInAnyOrderEntriesOf(EXPECTED_SIGN_CENSUS);

        assertThat(this.stagedRecords.stream().filter(record -> record.amount().signum() >= 0).count())
                .as("{ and A through I denote a non-negative value, giving 250 rows")
                .isEqualTo(EXPECTED_NON_NEGATIVE_ROWS);

        assertThat(this.stagedRecords.stream().filter(record -> record.amount().signum() < 0).count())
                .as("} and J through R denote a negative value, giving 50 rows")
                .isEqualTo(EXPECTED_NEGATIVE_ROWS);

        assertThat(this.stagedRecords.stream().noneMatch(record -> record.amount().signum() == 0))
                .as("no row carries a zero amount, so the non-negative branch of "
                        + "app/cbl/CBTRN02C.cbl:L548 is never entered with a zero")
                .isTrue();

        final BigDecimal smallest = this.stagedRecords.stream().map(StagedRecord::amount)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("the fixture cannot be empty here"));
        final BigDecimal largest = this.stagedRecords.stream().map(StagedRecord::amount)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("the fixture cannot be empty here"));

        assertThat(smallest)
                .as("the decoded minimum is -998.33; compared by compareTo because a NUMERIC(11,2) round trip "
                        + "returns scale 2 and BigDecimal.equals is scale-sensitive")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("-998.33"));

        assertThat(largest)
                .as("the decoded maximum is 999.77")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("999.77"));

        final StagedRecord first = this.stagedRecords.get(0);
        assertThat(first.transactionId()).as("record 1 identifier").isEqualTo("0000000000683580");
        assertThat(first.amount())
                .as("record 1 encodes 0000005047G, whose G overpunch contributes digit 7 and a positive sign")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("504.77"));

        final StagedRecord second = this.stagedRecords.get(1);
        assertThat(second.typeCode()).as("record 2 is an operator row").isEqualTo(OPERATOR_TYPE_CODE);
        assertThat(second.amount())
                .as("record 2 encodes 0000009190}, whose } overpunch contributes digit 0 and a negative sign, "
                        + "so a global text substitution would decode it as positive")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("-919.00"));

        final StagedRecord last = this.stagedRecords.get(this.stagedRecords.size() - 1);
        assertThat(last.transactionId()).as("record 300 identifier").isEqualTo("0000000996722787");
        assertThat(last.amount())
                .as("record 300 encodes 0000006032B, whose B overpunch contributes digit 2 and a positive sign")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("603.22"));
    }

    // ====================================================================================================
    // The load path: what the seed migration put into the staging table, and in what shape.
    // ====================================================================================================

    /**
     * Both {@code DALYTRAN-SOURCE} literals survive the load path, including the one
     * {@link TransactionSource} carries no constant for.
     *
     * <p>The enum resolves {@code POS TERM} and returns an empty {@link java.util.Optional} for
     * {@code OPERATOR} rather than throwing, and the staging column is a raw {@code CHAR(10)}. That
     * combination is what keeps the 50 operator rows loadable: a closed, throwing resolver on the load path
     * would break the fixture load outright.
     */
    @Test
    @DisplayName("app/cpy/CVTRA06Y.cpy: both source literals load, and the resolver never throws on either")
    void bothFixtureSourceLiteralsSurviveTheLoadPath() {
        assertThat(TransactionSource.fromCode(POS_SOURCE_LITERAL))
                .as("POS TERM is a declared literal of TransactionSource, assigned at "
                        + "app/cbl/COBIL00C.cbl:L222 and staged on 250 fixture rows")
                .contains(TransactionSource.POS_TERMINAL);

        assertThat(TransactionSource.fromCode(OPERATOR_SOURCE_LITERAL))
                .as("OPERATOR is staged data, not a literal any COBOL program assigns, so the resolver "
                        + "reports it as unmapped. It returns empty rather than throwing, which is precisely "
                        + "why the 50 operator rows load")
                .isEmpty();

        assertThat(TransactionSource.values())
                .as("TransactionSource carries exactly the two literals the corpus assigns: System at "
                        + "app/cbl/CBACT04C.cbl:L484 and POS TERM at app/cbl/COBIL00C.cbl:L222")
                .containsExactly(TransactionSource.SYSTEM, TransactionSource.POS_TERMINAL);

        final Map<String, Long> loadedSources = this.stagedRowsBeforePosting.stream().collect(
                Collectors.groupingBy(DailyTransaction::getTransactionSource, TreeMap::new,
                        Collectors.counting()));

        assertThat(loadedSources)
                .as("the staging table holds both literals at their full CHAR(10) width, 250 and 50, so "
                        + "neither was rejected nor normalised on the way in")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        POS_SOURCE_LITERAL, Long.valueOf(EXPECTED_NON_NEGATIVE_ROWS),
                        OPERATOR_SOURCE_LITERAL, Long.valueOf(EXPECTED_NEGATIVE_ROWS)));
    }

    /**
     * The staged rows preserve every declared field width byte-exactly, and
     * {@code DALYTRAN-PROC-TS} is 26 blanks on all 300 rows before posting.
     *
     * <p>The blank processing timestamp is the single most consequential schema fact in the pipeline: a
     * temporal column could not hold 26 spaces at all, so modelling it as anything but {@code CHAR(26)}
     * fails the load rather than the assertion.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L437-L438: staged widths are byte-exact and PROC-TS is blank pre-post")
    void stagedInputPreservesRecordGeometryAndBlankProcessingTimestamp() {
        assertThat(this.stagedRowsBeforePosting)
                .as("V3__seed_data.sql stages all 300 rows of app/data/ASCII/dailytran.txt")
                .hasSize(DAILY_TRANSACTION_FIXTURE.expectedRowCount());

        assertThat(this.stagedRowsBeforePosting).extracting(DailyTransaction::getProcTs)
                .as("every staged DALYTRAN-PROC-TS is 26 blanks. The column is CHAR(26): no temporal type can "
                        + "hold this value, and pre-filling it would erase the evidence that the timestamp is "
                        + "generated at post time")
                .containsOnly(BLANK_TIMESTAMP);

        assertThat(this.stagedRowsBeforePosting).allSatisfy(row -> {
            assertThat(row.getTransactionId())
                    .as("DALYTRAN-ID is PIC X(16)")
                    .hasSize(DALYTRAN_ID.width());
            assertThat(row.getTypeCode())
                    .as("DALYTRAN-TYPE-CD is PIC X(02)")
                    .hasSize(DALYTRAN_TYPE_CD.width());
            assertThat(row.getTransactionSource())
                    .as("DALYTRAN-SOURCE is PIC X(10); its padding is retained rather than trimmed")
                    .hasSize(DALYTRAN_SOURCE.width());
            assertThat(row.getDescription())
                    .as("DALYTRAN-DESC is PIC X(100); a trimming load would lose the fixed-width geometry the "
                            + "430-byte reject record is composed from")
                    .hasSize(DALYTRAN_DESC.width());
            assertThat(row.getMerchantZip())
                    .as("DALYTRAN-MERCHANT-ZIP is PIC X(10)")
                    .hasSize(DALYTRAN_MERCHANT_ZIP.width());
            assertThat(row.getCardNumber())
                    .as("DALYTRAN-CARD-NUM is PIC X(16)")
                    .hasSize(DALYTRAN_CARD_NUM.width());
            assertThat(row.getOrigTs())
                    .as("DALYTRAN-ORIG-TS is PIC X(26)")
                    .hasSize(TIMESTAMP_WIDTH);
            assertThat(row.getProcTs())
                    .as("DALYTRAN-PROC-TS is PIC X(26)")
                    .hasSize(TIMESTAMP_WIDTH);
        });

        final Map<String, BigDecimal> fixtureAmounts = this.stagedRecords.stream().collect(
                Collectors.toUnmodifiableMap(StagedRecord::transactionId, StagedRecord::amount));

        assertThat(this.stagedRowsBeforePosting).allSatisfy(row -> assertThat(row.getAmount())
                .as("the staged amount must equal the position-aware overpunch decoding of the fixture's own "
                        + "bytes; compared by compareTo because NUMERIC(11,2) reads back at scale 2")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(fixtureAmounts.get(row.getTransactionId())));

        assertThat(this.stagedRowsBeforePosting.stream()
                .filter(row -> row.getAmount().signum() < 0).count())
                .as("the seed load preserved all 50 negative amounts, so the sign was decoded position-aware "
                        + "and no absolute-value normalisation was applied on the way in")
                .isEqualTo(EXPECTED_NEGATIVE_ROWS);
    }

    // ====================================================================================================
    // The posting run: app/cbl/CBTRN02C.cbl:L202-L232.
    // ====================================================================================================

    /**
     * Every one of the 300 staged records is accounted for as either posted or rejected, and the two
     * outcomes are exclusive.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L206} increments the transaction count once per record and
     * {@code :L211-L216} branches into exactly one of two arms, so the counts must sum to the input and the
     * committed rows must agree with them.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L202-L219: posted plus rejected accounts for all 300 records")
    void everyStagedRecordIsAccountedForAsPostedOrRejected() {
        assertThat(this.processedCount)
                .as("WS-TRANSACTION-COUNT at app/cbl/CBTRN02C.cbl:L206 is incremented once per record read, "
                        + "so it must equal the 300 staged rows")
                .isEqualTo(DAILY_TRANSACTION_FIXTURE.expectedRowCount());

        assertThat(this.processedCount)
                .as("the two arms of app/cbl/CBTRN02C.cbl:L211-L216 are exclusive, so posted plus rejected "
                        + "must account for every record with none double-counted and none lost")
                .isEqualTo(this.postedTransactions.size() + this.rejectedCount);

        assertThat(this.rejectedCount)
                .as("the reject count cannot be negative and cannot exceed the input")
                .isBetween(0L, (long) DAILY_TRANSACTION_FIXTURE.expectedRowCount());

        assertThat(this.rejectedCount)
                .as("app/data/ASCII/acctdata.txt carries credit limits from 120.00 to 9750.00 while the "
                        + "fixture amounts reach 999.77, so OVERLIMIT TRANSACTION is reachable and at least "
                        + "one record must reject. No exact total is asserted: the Gate 1 baseline is Not "
                        + "available and a hand-derived total is model-sensitive rather than an oracle")
                .isPositive();

        assertThat(this.transactionCountBeforePosting)
                .as("the transaction table starts empty; V3__seed_data.sql seeds the ten other tables and "
                        + "leaves this one to the posting run to produce")
                .isZero();
    }

    /**
     * The run ends with the completed-with-rejects exit status, and that outcome is keyed on nothing but a
     * non-zero reject count.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L229-L231} is the whole determinant:
     * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}. There is no other contributor, and the batch
     * status must still be a successful completion, because return code 4 means "completed, with rejects" and
     * must never be conflated with a failure or an abend.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L227-L232: return code 4 iff the reject count exceeds zero")
    void runCompletesWithRejectsKeyedOnlyOnANonZeroRejectCount() {
        assertThat(this.rejectedCount)
                .as("the premise of the exit-status assertion: this run rejected at least one record")
                .isPositive();

        assertThat(this.postingExecution.getStatus())
                .as("return code 4 is a completion, not a failure: app/cbl/CBTRN02C.cbl:L227 is reached only "
                        + "by falling out of the mainline loop, and every failure path abends at "
                        + "9999-ABEND-PROGRAM instead")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(this.postingExecution.getExitStatus().getExitCode())
                .as("the reject count exceeded zero, so the run reports the completed-with-rejects status "
                        + "that stands in for MOVE 4 TO RETURN-CODE")
                .isEqualTo("COMPLETED WITH REJECTS");

        assertThat(this.postingExecution.getAllFailureExceptions())
                .as("a completed-with-rejects run records no failure; rejects are business outcomes and are "
                        + "never thrown")
                .isEmpty();
    }

    /**
     * {@code OVERLIMIT TRANSACTION} is the sole reject code reachable over the shipped fixtures, while 100,
     * 101 and 103 are provably unreachable and 109 is never consumed as a reject.
     *
     * <p>Each unreachability claim is proved from the fixture data rather than asserted from the outcome, so
     * the test states <em>why</em> the code cannot occur and then confirms that it did not.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L370-L422: 102 is the only reachable code; 100, 101, 103 cannot occur")
    void overlimitIsTheSoleReachableRejectCodeOverTheShippedFixtures() {
        final Set<String> crossReferencedCards = this.cardCrossReferenceRepository.findAll().stream()
                .map(CardCrossReference::getCardNumber)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(this.stagedRecords.stream().map(StagedRecord::cardNumber))
                .as("reject code 100 (INVALID CARD NUMBER FOUND, app/cbl/CBTRN02C.cbl:L385-L387) is "
                        + "unreachable: every staged card number resolves in the cross-reference, so the "
                        + "INVALID KEY arm of :L384 is never taken")
                .allMatch(crossReferencedCards::contains);

        assertThat(this.crossReferenceByCard.values())
                .as("reject code 101 (ACCOUNT RECORD NOT FOUND, :L397-L399) is unreachable: every "
                        + "XREF-ACCT-ID resolves in app/data/ASCII/acctdata.txt, so the INVALID KEY arm of "
                        + ":L396 is never taken")
                .allMatch(this.creditLimitByAccount::containsKey);

        final String earliestExpiry = this.expiryDateByAccount.values().stream()
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException(
                        "app/data/ASCII/acctdata.txt cannot be empty here"));

        assertThat(earliestExpiry)
                .as("reject code 103 (TRANSACTION RECEIVED AFTER ACCT EXPIRATION, :L417-L419) is unreachable: "
                        + ":L414 compares ACCT-EXPIRAION-DATE against the first ten characters of "
                        + "DALYTRAN-ORIG-TS as strings, and the earliest expiry in the account fixture is "
                        + "later than the single originating date every staged row carries. The field name is "
                        + "misspelled in app/cpy/CVACT01Y.cpy:L11 and the misspelling is preserved")
                .isGreaterThan(EXPECTED_ORIGIN_TIMESTAMP.substring(0, EXPIRY_COMPARISON_LENGTH));

        assertThat(this.creditLimitByAccount.values().stream().min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("the account fixture cannot be empty here")))
                .as("reject code 102 (OVERLIMIT TRANSACTION, :L410-L412) is reachable: the smallest credit "
                        + "limit is below the largest staged amount, and both cycle accumulators are zero on "
                        + "every seeded account so the temporary balance of :L403-L405 reduces to the amount "
                        + "itself on the first pass")
                .usingComparator(BigDecimal::compareTo)
                .isLessThan(new BigDecimal("999.77"));

        final List<Integer> emittedReasonCodes = rejectReasonCodes();

        assertThat(emittedReasonCodes)
                .as("one reason code per rejected record, matching WS-REJECT-COUNT")
                .hasSize((int) this.rejectedCount);

        assertThat(emittedReasonCodes)
                .as("every reject emitted over the shipped fixtures carries reason code 102; codes 100, 101 "
                        + "and 103 are absent because the data cannot reach them, and 109 is absent because "
                        + ":L556-L558 assigns it on the already-validated path where no reject record is "
                        + "written and the count is never incremented")
                .containsOnly(Integer.valueOf(RejectCode.OVERLIMIT_TRANSACTION.getCode()));

        assertThat(emittedReasonCodes)
                .as("the 102-overwritten-by-103 quirk of the sequential unguarded checks at :L407-L420 cannot "
                        + "be exercised by these fixtures, because 103 is unreachable. Exercising it needs a "
                        + "synthetic row, which belongs to the unit tier - never a fixture edit")
                .doesNotContain(
                        Integer.valueOf(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getCode()),
                        Integer.valueOf(RejectCode.INVALID_CARD_NUMBER.getCode()),
                        Integer.valueOf(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getCode()),
                        Integer.valueOf(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE.getCode()));
    }

    /**
     * {@link RejectCode} declares exactly five constants, each carrying its byte-exact literal description.
     *
     * <p>Five, not four: 109 is assigned at {@code app/cbl/CBTRN02C.cbl:L556-L558} on a genuinely reachable
     * path, so it is real code even though it is never consumed as a reject outcome. Removing it would delete
     * a real assignment; the pair 101 and 109 sharing one description is the source's own doing.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl: RejectCode holds exactly five constants with byte-exact literals")
    void rejectCodeEnumHoldsExactlyFiveConstantsWithByteExactDescriptions() {
        assertThat(RejectCode.values())
                .as("exactly five reject codes exist: 100, 101, 102, 103 and 109. 109 is retained because "
                        + ":L556-L558 is real code on a reachable path, not because it is ever consumed")
                .hasSize(5);

        assertThat(RejectCode.values()).extracting(RejectCode::getCode)
                .as("the five numeric codes, in source order")
                .containsExactly(100, 101, 102, 103, 109);

        assertThat(RejectCode.INVALID_CARD_NUMBER.getDescription())
                .as("app/cbl/CBTRN02C.cbl:L386, byte-exact")
                .isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getDescription())
                .as("app/cbl/CBTRN02C.cbl:L398, byte-exact")
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(RejectCode.OVERLIMIT_TRANSACTION.getDescription())
                .as("app/cbl/CBTRN02C.cbl:L411, byte-exact")
                .isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getDescription())
                .as("app/cbl/CBTRN02C.cbl:L418, byte-exact")
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE.getDescription())
                .as("app/cbl/CBTRN02C.cbl:L557 carries the identical text to :L398; the duplication is the "
                        + "source's and is preserved rather than disambiguated")
                .isEqualTo(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getDescription());

        assertThat(RejectCode.fromCode(RejectCode.NO_REJECT_REASON_CODE))
                .as("zero is not a reject code: it is the cleared state :L208 moves in before each "
                        + "validation, so the resolver reports it as unmapped rather than inventing a constant")
                .isEmpty();
    }

    // ====================================================================================================
    // Record geometry at the storage boundary.
    // ====================================================================================================

    /**
     * The emitted reject generation is composed of records of exactly 430 bytes, decomposing as 350 + 4 + 76.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L176-L182} declares {@code REJECT-TRAN-DATA PIC X(350)} plus
     * {@code VALIDATION-TRAILER PIC X(80)}, and the trailer as {@code PIC 9(04)} plus {@code PIC X(76)};
     * {@code app/jcl/POSTTRAN.jcl:L36} confirms the total independently as {@code LRECL=430}. The dataset is
     * {@code RECFM=F} with {@code BLKSIZE=0}, that is fixed <em>unblocked</em>, so the records carry no
     * delimiter and the object size is an exact multiple of 430.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L176-L182, app/jcl/POSTTRAN.jcl:L36: reject records are 430 = 350+4+76")
    void rejectGenerationRecordsAreExactlyFourHundredThirtyBytes() {
        assertThat(RejectCode.REJECT_TRAN_DATA_LENGTH)
                .as("REJECT-TRAN-DATA is PIC X(350), the whole DALYTRAN-RECORD image moved at :L447")
                .isEqualTo(TransactionWriter.RECORD_LENGTH);
        assertThat(RejectCode.FAIL_REASON_LENGTH)
                .as("WS-VALIDATION-FAIL-REASON is PIC 9(04)")
                .isEqualTo(4);
        assertThat(RejectCode.FAIL_REASON_DESC_LENGTH)
                .as("WS-VALIDATION-FAIL-REASON-DESC is PIC X(76)")
                .isEqualTo(76);
        assertThat(RejectCode.VALIDATION_TRAILER_LENGTH)
                .as("VALIDATION-TRAILER is PIC X(80), which the four-digit reason and 76-character "
                        + "description fill exactly")
                .isEqualTo(80);
        assertThat(RejectCode.REJECT_RECORD_LENGTH)
                .as("the reject record is 430 bytes: 350 + 4 + 76, matching LRECL=430 at "
                        + "app/jcl/POSTTRAN.jcl:L36")
                .isEqualTo(430);

        assertThat(this.rejectObjectKey)
                .as("the run rejected records, so it must have published exactly one generation key through "
                        + "the indexed job-level protocol at %s and %s",
                        RejectWriter.REJECT_OBJECT_KEYS_COUNT_ENTRY,
                        RejectWriter.rejectObjectKeysIndexEntry(0))
                .isNotNull();

        final ExecutionContext postingContext =
                stepExecution(DailyTransactionPostingJob.POSTING_STEP_BEAN_NAME).getExecutionContext();

        assertThat(postingContext.containsKey(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                .as("the writer also publishes the direct key into its own step context at %s. That entry "
                        + "exists only after the stream closes, which is why the job-level read goes through "
                        + "the indexed list instead: the framework runs afterStep, and therefore the job's "
                        + "promotion pass, before it closes the streams",
                        RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY)
                .isTrue();

        assertThat(postingContext.getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                .as("the step-level direct key and the job-level indexed entry name the same single object, "
                        + "so the two publication routes cannot drift")
                .isEqualTo(this.rejectObjectKey);

        assertThat(readCounter(postingContext, RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY))
                .as("the writer's own record count agrees with WS-REJECT-COUNT, so no reject was written "
                        + "without being counted or counted without being written")
                .isEqualTo(this.rejectedCount);

        assertThat(this.rejectObjectContent)
                .as("the published reject generation must be readable from the output bucket")
                .isNotNull();

        assertThat(this.rejectObjectContent.length() % RejectCode.REJECT_RECORD_LENGTH)
                .as("RECFM=F with BLKSIZE=0 is fixed unblocked, so records are concatenated with no "
                        + "separator and the object length is an exact multiple of 430. A remainder means a "
                        + "line terminator was introduced or a record was composed at the wrong width")
                .isZero();

        assertThat(this.rejectObjectContent.length())
                .as("one 430-byte record per rejected input record, and no more")
                .isEqualTo((int) this.rejectedCount * RejectCode.REJECT_RECORD_LENGTH);

        final Map<String, StagedRecord> stagedById = this.stagedRecords.stream().collect(
                Collectors.toUnmodifiableMap(StagedRecord::transactionId, Function.identity()));

        for (final String record : rejectRecords()) {
            final String payload = record.substring(0, RejectCode.REJECT_TRAN_DATA_LENGTH);
            final String reason = record.substring(RejectCode.REJECT_TRAN_DATA_LENGTH,
                    RejectCode.REJECT_TRAN_DATA_LENGTH + RejectCode.FAIL_REASON_LENGTH);
            final String description = record.substring(
                    RejectCode.REJECT_TRAN_DATA_LENGTH + RejectCode.FAIL_REASON_LENGTH);

            assertThat(payload)
                    .as("bytes 1-350 are the DALYTRAN-RECORD image moved at :L447, so the identifier sits in "
                            + "its original span and the record is re-serialisable")
                    .hasSize(RejectCode.REJECT_TRAN_DATA_LENGTH);

            assertThat(stagedById)
                    .as("the 350-byte payload must reproduce a staged input record byte-for-byte, so its "
                            + "identifier span must name one of the 300 staged rows")
                    .containsKey(DALYTRAN_ID.of(payload));

            assertThat(reason)
                    .as("bytes 351-354 are WS-VALIDATION-FAIL-REASON, a PIC 9(04) field rendered as four "
                            + "zero-padded digits")
                    .hasSize(RejectCode.FAIL_REASON_LENGTH)
                    .containsOnlyDigits()
                    .isEqualTo(RejectCode.OVERLIMIT_TRANSACTION.toFailReasonField());

            assertThat(description)
                    .as("bytes 355-430 are WS-VALIDATION-FAIL-REASON-DESC, a PIC X(76) field space-padded to "
                            + "its full width")
                    .hasSize(RejectCode.FAIL_REASON_DESC_LENGTH)
                    .isEqualTo(RejectCode.OVERLIMIT_TRANSACTION.toFailReasonDescField());

            assertThat(description.strip())
                    .as("the description text is the byte-exact literal of app/cbl/CBTRN02C.cbl:L411")
                    .isEqualTo(RejectCode.OVERLIMIT_TRANSACTION.getDescription());
        }
    }

    // ====================================================================================================
    // Posting semantics: the sign branch, the upsert branches and the generated timestamp.
    // ====================================================================================================

    /**
     * A negative amount is added to the cycle <em>debit</em> accumulator, leaving it negative, with no
     * absolute-value normalisation anywhere on the path.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L548-L552} adds the amount to the cycle credit when it is non-negative
     * and to the cycle debit otherwise - <em>adds</em>, so the debit accumulator legitimately holds negative
     * values. That is exactly why the over-limit formula at {@code :L403-L405} <em>subtracts</em> it.
     * Normalising the sign here would silently corrupt the over-limit arithmetic on the following cycle, a
     * defect that would not surface until a second batch run.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L545-L560: negative amounts add to the cycle debit, unnormalised")
    void negativeAmountsAccumulateOnTheCycleDebitSide() {
        final Map<Long, BigDecimal> expectedCreditDelta = new TreeMap<>();
        final Map<Long, BigDecimal> expectedDebitDelta = new TreeMap<>();

        for (final Transaction posted : this.postedTransactions) {
            final Long accountId = accountIdOf(posted);
            final BigDecimal amount = posted.getAmount();
            if (amount.signum() >= 0) {
                expectedCreditDelta.merge(accountId, amount, BigDecimal::add);
            } else {
                expectedDebitDelta.merge(accountId, amount, BigDecimal::add);
            }
        }

        assertThat(expectedDebitDelta)
                .as("the 50 negative fixture rows are the only cycle-debit coverage in the fixture set, so at "
                        + "least one posted transaction must have taken the ELSE arm of :L550")
                .isNotEmpty();

        assertThat(expectedDebitDelta.values())
                .as("every accumulated debit delta is negative, because :L551 ADDS a negative amount rather "
                        + "than adding its magnitude")
                .allMatch(delta -> delta.signum() < 0);

        for (final Map.Entry<Long, AccountCycleState> before : this.accountStateBeforePosting.entrySet()) {
            final Long accountId = before.getKey();
            final AccountCycleState after = this.accountStateAfterPosting.get(accountId);
            final BigDecimal creditDelta = after.cycleCredit().subtract(before.getValue().cycleCredit());
            final BigDecimal debitDelta = after.cycleDebit().subtract(before.getValue().cycleDebit());

            assertThat(creditDelta)
                    .as("the cycle-credit movement on account %d must equal the sum of its non-negative "
                            + "posted amounts, per :L549", accountId)
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(expectedCreditDelta.getOrDefault(accountId, BigDecimal.ZERO));

            assertThat(debitDelta)
                    .as("the cycle-debit movement on account %d must equal the sum of its negative posted "
                            + "amounts, sign retained, per :L551. A positive delta here would mean an "
                            + "absolute value was taken", accountId)
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(expectedDebitDelta.getOrDefault(accountId, BigDecimal.ZERO));

            assertThat(after.currentBalance().subtract(before.getValue().currentBalance()))
                    .as("ACCT-CURR-BAL takes every posted amount regardless of sign, per :L547, so its "
                            + "movement on account %d is the sum of both accumulator movements", accountId)
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(creditDelta.add(debitDelta));
        }

        assertThat(this.accountStateAfterPosting.values().stream()
                .anyMatch(state -> state.cycleDebit().signum() < 0))
                .as("at least one account ends the run with a negative cycle debit, which is the observable "
                        + "signature of the unnormalised sign branch")
                .isTrue();
    }

    /**
     * An absent category-balance row is created rather than treated as an error, and the run does not abend.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L481} accepts file status {@code '00'} <strong>or</strong> {@code '23'},
     * so a record-not-found read is an accepted control path that dispatches to
     * {@code 2700-A-CREATE-TCATBAL-REC} at {@code :L503}; a blanket not-found-to-exception rule would abend
     * the run here instead.
     *
     * <p>The shipped fixtures exercise <em>both</em> branches without any synthetic data:
     * {@code app/data/ASCII/tcatbal.txt} seeds only the {@code 01}/{@code 0001} key for each account, so the
     * 250 type-01 rows take the rewrite branch while the 50 type-03 rows find no row and take the create
     * branch.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L467-L528: an absent TCATBALF row is created, not treated as an error")
    void absentCategoryBalanceRowIsCreatedRatherThanTreatedAsAnError() {
        assertThat(this.categoryBalanceKeysBeforePosting)
                .as("app/data/ASCII/tcatbal.txt seeds one row per account, all on the 01/0001 key")
                .hasSize(EXPECTED_DISTINCT_CARD_NUMBERS)
                .allMatch(key -> key.endsWith("|" + POS_TYPE_CODE + "|1"));

        final Set<String> expectedCreatedKeys = this.postedTransactions.stream()
                .filter(posted -> OPERATOR_TYPE_CODE.equals(posted.getTypeCode().strip()))
                .map(posted -> accountIdOf(posted) + "|" + OPERATOR_TYPE_CODE + "|"
                        + posted.getCategoryCode())
                .collect(Collectors.toUnmodifiableSet());

        assertThat(expectedCreatedKeys)
                .as("the 50 type-03 rows have no seeded category-balance row, so any that posted must have "
                        + "driven the create branch at :L503")
                .isNotEmpty();

        assertThat(this.categoryBalanceKeysAfterPosting)
                .as("no seeded key is removed: :L526 rewrites in place and nothing deletes")
                .containsAll(this.categoryBalanceKeysBeforePosting);

        assertThat(this.categoryBalanceKeysAfterPosting)
                .as("every 03/0001 key a posted record needed now exists, which is only possible if file "
                        + "status '23' was accepted as success at :L481 rather than raised as an error")
                .containsAll(expectedCreatedKeys);

        assertThat(this.categoryBalanceKeysAfterPosting)
                .as("the store grew by exactly the keys the create branch had to add, so the upsert neither "
                        + "duplicated a key nor silently skipped one")
                .hasSize(this.categoryBalanceKeysBeforePosting.size() + expectedCreatedKeys.size());

        assertThat(this.postingExecution.getAllFailureExceptions())
                .as("the run recorded no failure, so the not-found read did not surface as an exception "
                        + "anywhere on the posting path")
                .isEmpty();
    }

    /**
     * The {@code CBTRN01C} pre-flight step exists inside this job, runs, and writes nothing.
     *
     * <p>{@code app/cbl/CBTRN01C.cbl} has six {@code SELECT} statements and a verb inventory of
     * {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY} only - zero {@code WRITE}, zero
     * {@code REWRITE}, zero {@code DELETE} - and it has no JCL job of its own, so it folds into this job as
     * an explicitly labelled read-only pre-flight rather than becoming a seventh job.
     */
    @Test
    @DisplayName("app/cbl/CBTRN01C.cbl: the read-only pre-flight step runs inside POSTTRAN and writes nothing")
    void preFlightStepRunsAndWritesNothing() {
        assertThat(this.dailyTransactionPostingJob.getName())
                .as("the job is named for its JCL member, app/jcl/POSTTRAN.jcl")
                .isEqualTo("POSTTRAN");

        assertThat(this.dailyTransactionPostingJob)
                .as("the job must expose its steps so the pre-flight can be shown to be part of it rather "
                        + "than a separate job")
                .isInstanceOf(StepLocator.class);

        assertThat(((StepLocator) this.dailyTransactionPostingJob).getStepNames())
                .as("both programs live in one job: CBTRN01C as the pre-flight and CBTRN02C as the posting "
                        + "step. CBTRN01C has no distinct JCL job, so a standalone job would be an invention")
                .containsExactlyInAnyOrder(
                        DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME,
                        DailyTransactionPostingJob.POSTING_STEP_BEAN_NAME);

        final StepExecution preFlight = stepExecution(
                DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME);

        assertThat(preFlight.getStatus())
                .as("the pre-flight ran to completion; the flow gates the posting step behind it")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(preFlight.getWriteCount())
                .as("the pre-flight writes nothing at all, which is the observable form of the program's verb "
                        + "inventory: WRITE 0, REWRITE 0, DELETE 0")
                .isZero();

        assertThat(preFlight.getCommitCount())
                .as("a read-only step commits no business work")
                .isLessThanOrEqualTo(1);

        final StepExecution posting = stepExecution(DailyTransactionPostingJob.POSTING_STEP_BEAN_NAME);

        assertThat(posting.getStatus())
                .as("the posting step completed; return code 4 is a completion, not a failure")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(posting.getReadCount())
                .as("the posting step read every one of the 300 staged rows")
                .isEqualTo(DAILY_TRANSACTION_FIXTURE.expectedRowCount());
    }

    /**
     * Every generated processing timestamp is the injected fixed clock rendered in the batch flavour,
     * {@code yyyy-MM-dd-HH.mm.ss.SS0000}.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L149} documents the shape and {@code :L692-L705} builds it: a hyphen
     * between the day and the hour, hundredths, then the four literal zeros {@code :L701} moves into
     * {@code DB2-REST}. It is never nanoseconds, and it is not the online flavour, which carries a space at
     * position 11. The value is derived from the injected clock rather than restated, so the assertion cannot
     * drift from the bean the production tier actually consumes.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L692-L705: TRAN-PROC-TS is the fixed clock as yyyy-MM-dd-HH.mm.ss.SS0000")
    void processingTimestampsComeFromTheInjectedFixedClock() {
        final String expected = BATCH_TIMESTAMP_FORMAT.format(this.clock.instant().atZone(this.clock.getZone()))
                + BATCH_TIMESTAMP_TRAILING_ZEROS;

        assertThat(expected)
                .as("the batch timestamp is 26 characters, matching TransactionPostingProcessor's declared "
                        + "width and the CHAR(26) column")
                .hasSize(TransactionPostingProcessor.DB2_FORMAT_TS_WIDTH);

        assertThat(expected.charAt(EXPIRY_COMPARISON_LENGTH))
                .as("position 11 is a hyphen in the batch flavour, per :L702, where the online flavour carries "
                        + "a space; the three flavours are not interchangeable")
                .isEqualTo('-');

        assertThat(expected)
                .as("the last four characters are the literal zeros of :L701, so the field never carries "
                        + "nanosecond precision")
                .endsWith(BATCH_TIMESTAMP_TRAILING_ZEROS);

        assertThat(this.postedTransactions)
                .as("the run posted at least one transaction, or there is no generated timestamp to assert on")
                .isNotEmpty();

        assertThat(this.postedTransactions).extracting(Transaction::getProcTs)
                .as("every posted TRAN-PROC-TS is the injected clock rendered in the batch flavour. A value "
                        + "outside this is the signature of a wall-clock read, which would also push every "
                        + "processing date outside the report's inclusive window and yield a plausible-looking "
                        + "empty report rather than an error")
                .containsOnly(expected);

        assertThat(this.postedTransactions).extracting(Transaction::getOrigTs)
                .as("TRAN-ORIG-TS is copied unchanged from the input at :L436, so it keeps the fixture's own "
                        + "pass-through flavour rather than being reformatted into the batch one")
                .containsOnly(EXPECTED_ORIGIN_TIMESTAMP);
    }

    /**
     * The posted transactions preserve the cross-reference and amount invariants that follow from the input:
     * every posted card number resolves in the cross-reference, every posted identifier names a staged row,
     * and each posted amount equals the fixture's own decoded value.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L424-L438: posted rows preserve the cross-reference and amount contract")
    void postedTransactionsPreserveCrossReferenceAndAmountInvariants() {
        final Map<String, StagedRecord> stagedById = this.stagedRecords.stream().collect(
                Collectors.toUnmodifiableMap(StagedRecord::transactionId, Function.identity()));

        assertThat(this.postedTransactions)
                .as("the run posted at least one transaction")
                .isNotEmpty();

        assertThat(this.postedTransactions).extracting(Transaction::getTransactionId)
                .as("each staged record posts at most once, so no identifier repeats")
                .doesNotHaveDuplicates();

        assertThat(this.postedTransactions).allSatisfy(posted -> {
            assertThat(this.crossReferenceByCard)
                    .as("every posted card number resolves in the cross-reference; a posted record whose card "
                            + "did not resolve would have been rejected with code 100 at :L385 instead")
                    .containsKey(posted.getCardNumber());

            assertThat(stagedById)
                    .as("every posted identifier names one of the 300 staged rows, so the run invented nothing")
                    .containsKey(posted.getTransactionId());

            final StagedRecord source = stagedById.get(posted.getTransactionId());

            assertThat(posted.getAmount())
                    .as("TRAN-AMT is moved from DALYTRAN-AMT unchanged at :L430, so it must equal the "
                            + "fixture's own position-aware decoding")
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(source.amount());

            assertThat(posted.getTransactionSource())
                    .as("TRAN-SOURCE is moved unchanged at :L428, retaining its full CHAR(10) width")
                    .isEqualTo(source.source());

            assertThat(posted.getTypeCode().strip())
                    .as("TRAN-TYPE-CD is moved unchanged at :L426")
                    .isEqualTo(source.typeCode());
        });

        final BigDecimal postedTotal = this.postedTransactions.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal expectedTotal = this.postedTransactions.stream()
                .map(posted -> stagedById.get(posted.getTransactionId()).amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(postedTotal)
                .as("the sum of posted amounts equals the sum of the corresponding fixture amounts, so no "
                        + "sign was normalised and no value was rounded on the way through")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(expectedTotal);
    }

    /**
     * The four named counters the observability layer publishes are registered, with the reject counter
     * bounded to the five reject codes and the amount counter carrying both sign series.
     *
     * <p>The legacy system has no instrumentation beyond {@code DISPLAY}, so these four replace the
     * end-of-run counter displays at {@code app/cbl/CBTRN02C.cbl:L227-L228}. Meter values are
     * double-precision by Micrometer's own API, so they are examined only for presence and accumulation here;
     * every monetary claim in this class is asserted against the database in {@link BigDecimal}.
     */
    @Test
    @DisplayName("app/cbl/CBTRN02C.cbl:L227-L228: the four named counters replace the end-of-run displays")
    void fourNamedBatchCountersAreRegistered() {
        assertThat(Search.in(this.meterRegistry).name(METRIC_RECORDS_PROCESSED).meters())
                .as("the processed-records counter replaces DISPLAY 'TRANSACTIONS PROCESSED :' at :L227")
                .isNotEmpty();

        assertThat(Search.in(this.meterRegistry).name(METRIC_AUTHENTICATION_ATTEMPTS).meters())
                .as("the authentication-attempts counter is registered, so the named set is complete even "
                        + "though this batch tier does not drive it")
                .isNotEmpty();

        final List<String> rejectCodeTags =
                Search.in(this.meterRegistry).name(METRIC_RECORDS_REJECTED).meters().stream()
                        .map(meter -> meter.getId().getTag(TAG_REJECT_CODE))
                        .sorted()
                        .toList();

        assertThat(rejectCodeTags)
                .as("the rejected-records counter replaces DISPLAY 'TRANSACTIONS REJECTED  :' at :L228 and is "
                        + "tagged by a bounded reject code: exactly the five constants, so an unbounded tag "
                        + "cannot cause a cardinality explosion")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(RejectCode.values())
                        .map(code -> Integer.toString(code.getCode()))
                        .sorted()
                        .toList());

        final List<String> signTags =
                Search.in(this.meterRegistry).name(METRIC_TRANSACTION_AMOUNT_TOTAL).meters()
                        .stream()
                        .map(meter -> meter.getId().getTag(TAG_SIGN))
                        .sorted()
                        .toList();

        assertThat(signTags)
                .as("the amount counter carries both series of the sign branch at :L548-L552; a Prometheus "
                        + "counter may not go negative, so the debit magnitude is accumulated and the sign "
                        + "survives as the series tag rather than being lost")
                .containsExactlyInAnyOrder(SIGN_CREDIT, SIGN_DEBIT);

        final Meter debitSeries = Search.in(this.meterRegistry)
                .name(METRIC_TRANSACTION_AMOUNT_TOTAL)
                .tag(TAG_SIGN, SIGN_DEBIT)
                .meter();

        assertThat(debitSeries)
                .as("the debit series must exist for the cycle-debit branch to be observable at all")
                .isNotNull();
    }

    /**
     * The Gate 1 boundary-parity baseline is reported as {@code Not available}, with the needed evidence
     * stated verbatim and no baseline artefact produced.
     */
    @Test
    @DisplayName("Rule 1 clause F: the Gate 1 boundary baseline is reported Not available with what is needed")
    void gateOneBaselineIsReportedAsNotAvailable() {
        final String report = reportGateOneBaselineAvailability();

        assertThat(report)
                .as("Rule 1 clause F requires missing information to be stated as Not available rather than "
                        + "filled with an invention")
                .contains("Not available");

        assertThat(report)
                .as("the needed evidence is stated verbatim, so a later run knows exactly what would close "
                        + "the gate")
                .contains("a captured DALYREJS 430-byte reject dataset plus the resulting TRANSACT / "
                        + "ACCTDATA / TCATBALF images from a real POSTTRAN execution at a known input state.");

        assertThat(report)
                .as("the report explains why no expected total is asserted, so the omission reads as a "
                        + "reasoned position rather than an oversight")
                .contains("model-sensitive");
    }

    // ====================================================================================================
    // Remaining private helpers.
    // ====================================================================================================

    /**
     * Splits the emitted reject generation into its fixed 430-byte records.
     *
     * @return one string per record, each exactly 430 characters, or an empty list when nothing was rejected
     */
    private List<String> rejectRecords() {
        if (this.rejectObjectContent == null) {
            return List.of();
        }
        final List<String> records = new ArrayList<>();
        for (int offset = 0; offset + RejectCode.REJECT_RECORD_LENGTH <= this.rejectObjectContent.length();
                offset += RejectCode.REJECT_RECORD_LENGTH) {
            records.add(this.rejectObjectContent.substring(offset,
                    offset + RejectCode.REJECT_RECORD_LENGTH));
        }
        return List.copyOf(records);
    }

    /**
     * Extracts the four-digit reason code from every emitted reject record.
     *
     * @return the reason codes in emission order, never {@code null}
     */
    private List<Integer> rejectReasonCodes() {
        return rejectRecords().stream()
                .map(record -> Integer.valueOf(record.substring(RejectCode.REJECT_TRAN_DATA_LENGTH,
                        RejectCode.REJECT_TRAN_DATA_LENGTH + RejectCode.FAIL_REASON_LENGTH)))
                .toList();
    }

    /**
     * Resolves the account a posted transaction belongs to, through the cross-reference the posting path uses.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L394} keys the account read on {@code XREF-ACCT-ID}, so the card-to-
     * account mapping is the only correct route from a posted transaction to its account.
     *
     * @param posted the committed transaction; must not be {@code null}
     * @return the account identifier, never {@code null}
     * @throws IllegalStateException if the card does not resolve, which would contradict the reject-code 100
     *     unreachability this class proves separately
     */
    private Long accountIdOf(final Transaction posted) {
        final String accountId = this.crossReferenceByCard.get(posted.getCardNumber());
        if (accountId == null) {
            throw new IllegalStateException(
                    "A posted transaction's card number does not resolve in app/data/ASCII/cardxref.txt. "
                            + "That contradicts the reject-code 100 unreachability proof, so either the "
                            + "fixture changed or the posting path accepted a record it should have rejected "
                            + "at app/cbl/CBTRN02C.cbl:L385. The card number is not quoted here because it is "
                            + "cardholder data.");
        }
        return Long.valueOf(accountId);
    }

    /**
     * Finds one step execution of the single posting run by step name.
     *
     * @param stepName the authored step bean name to locate
     * @return the execution, never {@code null}
     * @throws IllegalStateException if the run holds no step of that name, naming the steps it does hold
     */
    private StepExecution stepExecution(final String stepName) {
        return this.postingExecution.getStepExecutions().stream()
                .filter(execution -> stepName.equals(execution.getStepName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The posting run holds no step named '"
                        + stepName + "'; it holds "
                        + this.postingExecution.getStepExecutions().stream()
                                .map(StepExecution::getStepName)
                                .sorted()
                                .toList()));
    }
}
