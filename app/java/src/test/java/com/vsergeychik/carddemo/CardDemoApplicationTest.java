package com.vsergeychik.carddemo;

import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig;
import com.vsergeychik.carddemo.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole-graph context-load gate for this module - validation gate <strong>G3</strong> - and the
 * contract suite for {@link CardDemoApplication}, its composition root.
 *
 * <h2>What this suite is for</h2>
 * <p>Everything the entry point does is <em>declarative</em>, and a declarative mistake is silent. A
 * dropped entry in the component-scan list does not fail to compile: it removes an entire domain
 * package from component scope, and the first symptom is a {@code NoSuchBeanDefinitionException}
 * raised at runtime, a long way from the edit that caused it. A configuration class named after the
 * bean it publishes does not fail to compile either: bean-definition overriding is disabled by
 * default in Spring Boot, so the two definitions neither merge nor shadow - the context refuses to
 * start at all, and every unit test in the module still passes, because no unit test registers a
 * bean definition. A {@code @Value} placeholder that nothing resolves behaves the same way.
 *
 * <p>So this suite starts <strong>the real application context</strong>, under the fixture-backed
 * {@code test} profile, and asserts the shape of the graph that comes back: every controller, every
 * batch job, every dataset repository, every writer, every configuration class and every piece of
 * batch infrastructure the migration is specified to publish, and nothing beyond them. It is the one
 * place in the module where the entire wiring graph is exercised at once; the per-package suites
 * that delegate the whole-graph gate here (see {@code config/WebConfigTest},
 * {@code config/BatchConfigTest} and {@code config/CobolCharsetConfigTest}) deliberately stay
 * context-free slices.
 *
 * <h2>What it deliberately does not do</h2>
 * <p>It asserts <strong>bean presence and wiring only</strong>. No business logic, no arithmetic, no
 * HTTP request, no {@code JobLauncher}: those belong to the per-package unit suites and to the parity
 * harness, which reach services and jobs directly so that a numeric assertion has neither a servlet
 * nor a job launcher in its path (gate G51). Nothing here claims anything about COBOL parity -
 * expected-value provenance is {@code parity/ParityHarness}'s subject, not this file's.
 *
 * <h2>The counts are what the graph actually contains, not what prose predicted</h2>
 * <p>Two numbers in the plan's prose do not survive contact with the source, and this suite asserts
 * the verified ones:
 * <ul>
 *   <li><strong>Eighteen {@code @RestController} beans, not seventeen.</strong> The seventeen screen
 *       controllers are the seventeen CICS online programs, one each. The eighteenth is
 *       {@link WebConfig.CobolErrorEndpoint}, which exists to <em>replace</em> Spring Boot's
 *       {@code BasicErrorController} - declaring an {@code ErrorController} bean switches Boot's own
 *       off - so it is a substitution rather than an addition, and it is named explicitly below so
 *       that a genuinely unexpected eighteenth screen controller still fails.</li>
 *   <li><strong>Nine {@code Job} beans, not ten.</strong> Counting {@code EXEC PGM=} across
 *       {@code app/jcl} and {@code app/proc} yields eight distinct programs, plus {@code CBTRN01C},
 *       which no JCL invokes anywhere and which migrates as a runnable job with no trigger (gate
 *       G13) - nine. {@code app/java/pom.xml} records the same figure and identifies the cause of
 *       the plan's "ten" (CBCUS01C counted twice), {@code application.yml} declares exactly nine
 *       {@code carddemo.jobs} keys, and {@code BatchConfig.JobContracts} enforces those nine at
 *       startup. Asserting ten would fail against a correct module.</li>
 * </ul>
 * <p>Two prompt-mandated names also do <em>not</em> denote jobs: {@code StatementGenerationJobB} is
 * {@code CBSTM03A}'s data-access collaborator and is a {@code @Component}, and
 * {@code DateUtilityJob} is a called date-validation subprogram and is a {@code @Service} (gate
 * G12). Both are asserted to be absent from the job inventory rather than present in it.
 *
 * <h2>Why the inventories are fully-qualified names rather than imported types</h2>
 * <p>This file's declared dependencies are the module descriptor, the entry point, the two
 * configuration documents and the four {@code config/} classes. The four configuration classes are
 * therefore imported and asserted as compile-checked types. The seventeen controllers, nine jobs, ten
 * repositories, four writers and one reader are not among those dependencies, so they are asserted as
 * explicit fully-qualified name strings read back off the live bean graph. Nothing is lost: a renamed
 * or missing type fails here just as loudly, the inventory reads as data that can be diffed against
 * the plan, and the root test package does not acquire a compile-time edge to all nine domain
 * packages.
 *
 * <h2>Governing rules</h2>
 * <p>{@code review_rules} reports <strong>no user rules provided</strong> for this project - a single
 * line, which is the whole document - so no project rule governs this file and none has been
 * invented. Their absence is not licence to lower the bar: the enterprise practices the Agent Action
 * Plan puts in their place bind instead, and the ones that reach this file are:
 * <ul>
 *   <li><strong>B1 / B2</strong> - nothing outside the stack {@code app/java/pom.xml} already pins.
 *       JUnit Jupiter, AssertJ and the Spring test support arrive through
 *       {@code spring-boot-starter-test}; H2 is already test-scoped there. No dependency is added,
 *       and only Spring Boot 3.5.x / Spring Batch 5.2.x API is used - the 5.x job and step builders,
 *       never the builder-factory types that the 4.x line published and that Batch 5 removed.</li>
 *   <li><strong>B3</strong> - the reference trees are untouched. Nothing here writes anywhere, and
 *       nothing reads {@code app/cbl}, {@code app/cpy}, {@code app/bms}, {@code app/jcl} or
 *       {@code app/data}: the {@code test} profile resolves everything from the classpath.</li>
 *   <li><strong>B4</strong> - no silent scope creep. The assertions are two-sided wherever the
 *       migration's surface is fixed, so an unexpected extra fails exactly as a missing one does, and
 *       the two prose-versus-source conflicts above are recorded rather than quietly rounded away.</li>
 *   <li><strong>B7</strong> - one command, {@code mvn -f app/java/pom.xml clean verify}, runs this
 *       non-interactively. The one time-bearing bean is asserted by its zone rule, never by reading
 *       an instant, so this suite has no dependence on the system clock.</li>
 *   <li><strong>B8</strong> - explicit over implicit: no wildcard import here (gate G52), every
 *       charset named, and assertions stated as named beans rather than opaque aggregates.</li>
 *   <li><strong>B9</strong> - constructor injection, and no static mutable state (gate G53). Every
 *       constant below is {@code static final} and immutable; the injected context is a
 *       {@code private final} instance field.</li>
 *   <li><strong>B12</strong> - environmental limits are recorded, not absorbed. Where the plan's
 *       letter cannot hold - the Micrometer API jars that arrive as non-optional transitives of two
 *       mandated starters - this suite asserts what is actually true and achievable: no registry
 *       bean, so nothing is ever collected or exported.</li>
 * </ul>
 *
 * <h2>Coverage</h2>
 * <p>{@code app/java/pom.xml} enforces BRANCH coverage of at least 0.90 per package. The root package
 * holds only {@link CardDemoApplication}, whose BRANCH counter total is zero, and JaCoCo skips a rule
 * whose counter total is zero - so this package is not a coverage violation and this file is not
 * required to manufacture branches. Its job is structural verification.
 *
 * @see CardDemoApplication
 */
@SpringBootTest(classes = CardDemoApplication.class)
@ActiveProfiles("test")
@DisplayName("CardDemoApplication - the whole-graph context-load gate (G3)")
class CardDemoApplicationTest {

    /** The published root package, and the prefix of every scanned package. */
    private static final String ROOT_PACKAGE = "com.vsergeychik.carddemo";

    /** The published fully-qualified name, which {@code app/java/pom.xml} carries as a string. */
    private static final String PUBLISHED_FQN = ROOT_PACKAGE + ".CardDemoApplication";

    /**
     * The eleven packages the entry point places in component scope, in the order the architecture
     * reads in: the two foundation packages, then the nine domain packages that hold the twenty-eight
     * translated programs.
     */
    private static final List<String> EXPECTED_SCAN_PACKAGES = List.of(
            ROOT_PACKAGE + ".common",
            ROOT_PACKAGE + ".config",
            ROOT_PACKAGE + ".account",
            ROOT_PACKAGE + ".card",
            ROOT_PACKAGE + ".customer",
            ROOT_PACKAGE + ".user",
            ROOT_PACKAGE + ".transaction",
            ROOT_PACKAGE + ".admin",
            ROOT_PACKAGE + ".billing",
            ROOT_PACKAGE + ".statement",
            ROOT_PACKAGE + ".util");

    /**
     * The seventeen screen controllers - one per CICS online program, and the whole online surface.
     *
     * <p>Grouped by domain package and listed in the order the plan's transaction table lists them:
     * sign-on and menus, then account, card, transaction and bill-payment, then user maintenance.
     * {@code COCRDSEC} / transaction {@code CDV1} is defined in {@code app/csd/CARDDEMO.CSD} but has
     * no source file anywhere, so it is deliberately absent (gate G14) - an eighteenth entry here
     * would be an invented program.
     */
    private static final List<String> SCREEN_CONTROLLERS = List.of(
            ROOT_PACKAGE + ".user.SignOnController",
            ROOT_PACKAGE + ".admin.MainMenuController",
            ROOT_PACKAGE + ".admin.AdminMenuController",
            ROOT_PACKAGE + ".account.AccountViewController",
            ROOT_PACKAGE + ".account.AccountUpdateController",
            ROOT_PACKAGE + ".card.CardListController",
            ROOT_PACKAGE + ".card.CardSelectController",
            ROOT_PACKAGE + ".card.CardUpdateController",
            ROOT_PACKAGE + ".transaction.TransactionMenuController",
            ROOT_PACKAGE + ".transaction.TransactionAddController",
            ROOT_PACKAGE + ".transaction.TransactionViewController",
            ROOT_PACKAGE + ".transaction.ReportRequestController",
            ROOT_PACKAGE + ".billing.BillPaymentController",
            ROOT_PACKAGE + ".user.UserMenuController",
            ROOT_PACKAGE + ".user.UserAddController",
            ROOT_PACKAGE + ".user.UserUpdateController",
            ROOT_PACKAGE + ".user.UserDeleteController");

    /**
     * The nine {@link Job} bean names, which are also the names a submission looks a job up by.
     *
     * <p>Eight are invoked by {@code EXEC PGM=} in {@code app/jcl} or {@code app/proc};
     * {@code transactionPostingJob} is the ninth and is invoked by nothing anywhere, which is exactly
     * why it has to be asserted present (gate G13).
     */
    private static final List<String> JOB_BEAN_NAMES = List.of(
            "accountBalanceJob",
            "accountBalanceReaderJob",
            "accountBalanceUpdateJob",
            "accountInterestCalcJob",
            "customerFileReaderJob",
            "transactionValidationJob",
            "transactionReportJob",
            "statementGenerationJobA",
            "transactionPostingJob");

    /**
     * The nine job {@code @Configuration} beans, each named so that it cannot collide with the job it
     * publishes.
     *
     * <p>This is the collision the plan's naming makes almost inevitable and that only a started
     * context can detect: component scanning names a configuration bean after its class, so
     * {@code AccountBalanceJob} would be registered as {@code accountBalanceJob} - the very name its
     * {@code @Bean} method publishes the job under. Each class therefore sets an explicit
     * configuration bean name, and both names are asserted to exist and to differ.
     */
    private static final List<String> JOB_CONFIGURATION_BEAN_NAMES = List.of(
            "accountBalanceJobConfiguration",
            "accountBalanceReaderJobConfiguration",
            "accountBalanceUpdateJobConfiguration",
            "accountInterestCalcJobConfiguration",
            "customerFileReaderJobConfiguration",
            "transactionValidationJobConfiguration",
            "transactionReportJobConfiguration",
            "statementGenerationJobAConfiguration",
            "transactionPostingJobConfiguration");

    /**
     * The ten {@code @Repository} beans - one per base dataset, and the entire repository surface.
     *
     * <p>The plan counts twelve repositories, and that is a <em>logical</em> count of dataset access
     * paths rather than a file count. There is no {@code DisclosureGroupRepository} and no
     * {@code TrnxRepository}: {@code DISCGRP} is read by the interest job's own access collaborator,
     * and {@code TRNXFILE} is owned by {@code StatementGenerationJobB}, which is faithful to
     * {@code CBSTM03B.CBL} declaring all four of the statement job's input files. Both absences are
     * asserted, so this list cannot drift into a bean that does not exist.
     *
     * <p>The two alternate indexes are finder methods on the base repositories rather than
     * repositories of their own (gate G45), which is why {@code CARDAIX} and {@code CXACAIX} add no
     * entry here.
     */
    private static final List<String> DATASET_REPOSITORIES = List.of(
            ROOT_PACKAGE + ".account.AccountRepository",
            ROOT_PACKAGE + ".card.CardRepository",
            ROOT_PACKAGE + ".card.CardXrefRepository",
            ROOT_PACKAGE + ".customer.CustomerRepository",
            ROOT_PACKAGE + ".transaction.TransactionRepository",
            ROOT_PACKAGE + ".transaction.DalyTranRepository",
            ROOT_PACKAGE + ".transaction.TranCatBalRepository",
            ROOT_PACKAGE + ".transaction.TranTypeRepository",
            ROOT_PACKAGE + ".transaction.TranCategoryRepository",
            ROOT_PACKAGE + ".user.SecUserRepository");

    /**
     * The four fixed-width output writers and the one parameter reader, with the JCL-declared width
     * each of them owns.
     *
     * <p>{@code DALYREJS} is 430 bytes, {@code TRANREPT} is 133, {@code STMTFILE} is 80 and
     * {@code HTMLFILE} is 100 - the last taken from the step that creates it, not the pre-delete step
     * that declares 80 (gate G20). {@code DateParmReader} is the fifth member because
     * {@code CBTRN03C} reads its report date range from the {@code DATEPARM} dataset rather than from
     * a {@code PARM}, so it is a reader rather than a job parameter. The widths themselves are each
     * writer's own suite to assert; what is asserted here is that all five beans exist.
     */
    private static final List<String> DATASET_WRITERS_AND_READERS = List.of(
            ROOT_PACKAGE + ".transaction.DalyRejectWriter",
            ROOT_PACKAGE + ".transaction.TranReportWriter",
            ROOT_PACKAGE + ".statement.StatementTextWriter",
            ROOT_PACKAGE + ".statement.StatementHtmlWriter",
            ROOT_PACKAGE + ".transaction.DateParmReader");

    /**
     * The two called subprograms, whose prompt-mandated names end in "Job" and which are not jobs
     * (gate G12).
     *
     * <p>Neither has an {@code EXEC PGM=} anywhere in {@code app/jcl} or {@code app/proc}.
     * {@code CBSTM03B} is called at thirteen sites by {@code CBSTM03A} and {@code CSUTLDTC} is called
     * from two online programs, so both are injected collaborators.
     */
    private static final List<String> SUBPROGRAM_BEAN_NAMES = List.of(
            "statementGenerationJobB",
            "dateUtilityJob");

    /**
     * The two repository types the plan's logical count could be misread as requiring, asserted
     * absent.
     *
     * <p>Stated as an assertion rather than as a comment because a plausible-looking bean lookup on a
     * type that does not exist is the failure this list exists to make impossible.
     */
    private static final List<String> ABSENT_REPOSITORY_TYPES = List.of(
            ROOT_PACKAGE + ".account.DisclosureGroupRepository",
            ROOT_PACKAGE + ".statement.TrnxRepository");

    /**
     * The excluded-technology boundary, as the one representative type each family cannot exist
     * without.
     *
     * <p>Every entry is a type that would be on the classpath if the family had been introduced, so
     * the check is a dependency-boundary assertion rather than a bean-count coincidence: no
     * authentication or credential-hashing stack (gate G41), no object-relational mapper and no
     * schema-migration tool (gate G44), no API-documentation generator and no reactive web stack.
     */
    private static final Map<String, String> EXCLUDED_TECHNOLOGY_TYPES = Map.of(
            "Spring Security", "org.springframework.security.web.SecurityFilterChain",
            "Jakarta Persistence", "jakarta.persistence.EntityManagerFactory",
            "Hibernate", "org.hibernate.SessionFactory",
            "Flyway", "org.flywaydb.core.Flyway",
            "Liquibase", "liquibase.integration.spring.SpringLiquibase",
            "springdoc-openapi", "org.springdoc.core.models.GroupedOpenApi",
            "Spring WebFlux", "org.springframework.web.reactive.function.client.WebClient");

    /**
     * Package prefixes no bean in this context may come from, matching the families above that could
     * otherwise contribute a bean silently.
     */
    private static final List<String> EXCLUDED_BEAN_PACKAGE_PREFIXES = List.of(
            "org.springframework.security",
            "jakarta.persistence",
            "org.hibernate",
            "org.flywaydb",
            "liquibase");

    /**
     * The two Micrometer registry types that must have no bean, even though their API jars are on the
     * classpath.
     *
     * <p>Three jars - {@code micrometer-observation}, {@code micrometer-commons} and
     * {@code micrometer-core} - are irreducible transitives of {@code spring-boot-starter-web} and
     * {@code spring-boot-starter-batch}, both of which the plan mandates: {@code AbstractJob} and
     * {@code AbstractStep}, the base classes of every Spring Batch job and step, declare fields and
     * public setters typed on these two registry interfaces, so excluding them would stop the module
     * loading rather than slim it. {@code app/java/pom.xml} records that conflict in full. What the
     * exclusion actually asks for - nothing collected, nothing exported, no observability stack
     * operated - is what is asserted here, at the only place it can be: the bean graph.
     */
    private static final List<String> ABSENT_REGISTRY_TYPES = List.of(
            "io.micrometer.observation.ObservationRegistry",
            "io.micrometer.core.instrument.MeterRegistry");

    /**
     * Jar-name prefixes of the <em>only</em> artifacts from the excluded observability family that may
     * appear on the runtime classpath - the three the mandated starters make irreducible.
     *
     * <p>A fourth {@code micrometer-*} artifact appearing here is a regression, whether it arrives by
     * a new declaration or by a starter's transitive graph shifting under a version bump, and
     * {@link TheExcludedTechnologyBoundary} fails the build when one does. Prefixes rather than exact
     * filenames, so a managed version change is not mistaken for a new artifact.
     */
    private static final List<String> IRREDUCIBLE_OBSERVABILITY_JAR_PREFIXES = List.of(
            "micrometer-observation-",
            "micrometer-commons-",
            "micrometer-core-");

    /**
     * Jar-name prefixes {@code app/java/pom.xml} excludes from {@code spring-boot-starter-batch}, and
     * which must therefore be absent from the runtime classpath.
     *
     * <p>Both are runtime-scope dependencies of {@code micrometer-core} reached from exactly two of its
     * classes - {@code AbstractTimer} for pause detection and {@code TimeWindowPercentileHistogram} for
     * percentile storage - neither of which is reachable without a registered {@code MeterRegistry}, and
     * {@link #ABSENT_REGISTRY_TYPES} asserts there is none. They are the removable part of the excluded
     * surface, so they are removed; this list is what keeps them removed.
     */
    private static final List<String> REMOVED_OBSERVABILITY_JAR_PREFIXES = List.of(
            "HdrHistogram-",
            "LatencyUtils-");

    /**
     * The marker types of the two removed artifacts, checked by resolution as well as by jar name.
     *
     * <p>The two checks are complementary rather than redundant: jar-name enumeration reads manifests
     * and would under-report an artifact that carried none, while type resolution is blind to an
     * artifact that is present but whose classes are never named. Together they close both gaps.
     */
    private static final List<String> REMOVED_OBSERVABILITY_TYPES = List.of(
            "org.HdrHistogram.Histogram",
            "org.LatencyUtils.PauseDetector");

    /**
     * Types whose mere presence would mean an observability stack had been introduced - a registry
     * implementation, an exporter, a tracing bridge or Actuator itself.
     *
     * <p>{@link #ABSENT_REGISTRY_TYPES} proves nothing is <em>wired</em>; this proves there is nothing
     * to wire. Every entry is a family AAP 0.5.6 excludes by name (Micrometer registries, Prometheus,
     * Jaeger and the tracing bridges that feed them) or the management surface that would expose them.
     */
    private static final Map<String, String> ABSENT_OBSERVABILITY_TYPES = Map.of(
            "Micrometer Prometheus registry", "io.micrometer.prometheusmetrics.PrometheusMeterRegistry",
            "Micrometer tracing", "io.micrometer.tracing.Tracer",
            "Micrometer Jakarta 9 instrumentation", "io.micrometer.jakarta9.instrument.jms.JmsInstrumentation",
            "Spring Boot Actuator", "org.springframework.boot.actuate.health.HealthIndicator",
            "Actuator metrics auto-configuration",
            "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
            "OpenTelemetry", "io.opentelemetry.api.OpenTelemetry",
            "Brave", "brave.Tracing",
            "Zipkin", "zipkin2.Span");

    /** The resource every jar on the classpath carries, and the handle used to enumerate them. */
    private static final String MANIFEST_RESOURCE = "META-INF/MANIFEST.MF";

    /** Separates a jar URL's archive part from the entry inside it. */
    private static final String JAR_ENTRY_SEPARATOR = "!/";

    /**
     * Jars the classpath enumeration must find for its result to be trusted, and the floor on how many
     * it must see in total.
     *
     * <p>Surefire runs the suite through a manifest-only booter jar, so an enumeration that silently saw
     * only that one entry would let every absence assertion below pass over an empty search. These two
     * self-checks fail loudly instead: one jar from the mandated web/batch stack, one from the very
     * family under audit, and a count no partial view could reach.
     */
    private static final List<String> CLASSPATH_PROBE_ANCHORS = List.of(
            "spring-core-",
            "micrometer-core-");

    /** The smallest jar count a complete view of this module's test classpath can have. */
    private static final int CLASSPATH_PROBE_FLOOR = 20;

    /**
     * The test-only package that holds the stereotyped fixtures, and the one package under the root
     * that must stay <em>out</em> of the scan list.
     */
    private static final String TEST_SUPPORT_PACKAGE = ROOT_PACKAGE + ".testsupport";

    /** The suffix of a compiled class file. */
    private static final String CLASS_SUFFIX = ".class";

    /** The bytecode descriptor of {@code @Controller}, as it appears in a class file's constant pool. */
    private static final String CONTROLLER_DESCRIPTOR =
            "Lorg/springframework/stereotype/Controller;";

    /** The bytecode descriptor of {@code @RestController}, which carries {@code @Controller}. */
    private static final String REST_CONTROLLER_DESCRIPTOR =
            "Lorg/springframework/web/bind/annotation/RestController;";

    /** Extracts the {@code mainClass} the Spring Boot plugin is configured with. */
    private static final Pattern MAIN_CLASS =
            Pattern.compile("<mainClass>\\s*([^<\\s]+)\\s*</mainClass>");

    /** Extracts the repackage layout, which decides which launcher the jar's manifest names. */
    private static final Pattern LAYOUT =
            Pattern.compile("<layout>\\s*([^<\\s]+)\\s*</layout>");

    /** The started context, injected through the constructor so this class holds no mutable state. */
    private final ApplicationContext context;

    /**
     * Receives the started application context.
     *
     * <p>Constructor injection, not field injection (practice B9): the context is captured once in a
     * {@code final} field, so nothing in this suite can replace it and no static holds it.
     * {@code @Autowired} is required rather than decorative - Spring's default test-constructor
     * autowire mode is {@code ANNOTATED}, so an unannotated constructor parameter would not be
     * resolved at all and JUnit would fail to instantiate this class.
     *
     * @param context the context refreshed from {@link CardDemoApplication} under the {@code test}
     *     profile; never {@code null}
     */
    @Autowired
    CardDemoApplicationTest(ApplicationContext context) {
        this.context = context;
    }

    @Nested
    @DisplayName("The context starts, under the profile that can actually start it")
    class TheContextStarts {

        @Test
        @DisplayName("the context is refreshed and active, so every assertion below reads a live graph")
        void theContextIsActive() {
            assertThat(context)
                    .as("the context is injected through the constructor; a null here means the "
                            + "refresh never happened")
                    .isNotNull();
            assertThat(context.getStartupDate())
                    .as("a refreshed context records the moment it started")
                    .isPositive();
            // A floor derived from the inventories this suite goes on to assert, rather than a round
            // number: the graph must be at least as large as the module's own declared surface.
            assertThat(context.getBeanDefinitionCount())
                    .as("the graph is populated, not an empty container")
                    .isGreaterThan(SCREEN_CONTROLLERS.size() + JOB_BEAN_NAMES.size()
                            + DATASET_REPOSITORIES.size());

            // Narrowed rather than injected as ConfigurableApplicationContext: what this suite needs
            // everywhere else is the read-only interface, and asking for the configurable one would
            // hand every test the ability to mutate the very graph it is asserting on.
            assertThat(context).isInstanceOf(ConfigurableApplicationContext.class);
            assertThat(((ConfigurableApplicationContext) context).isActive())
                    .as("active means refreshed and not yet closed")
                    .isTrue();
        }

        @Test
        @DisplayName("the 'test' profile is the only one active, so the H2 DataSource is what is wired")
        void theTestProfileIsActive() {
            // This is load-bearing, not incidental. application.yml ships a deliberately driver-free,
            // credential-free DataSource block, because indexed VSAM has no published JDBC driver and
            // the site-specific driver is a deployment-time input: that block cannot start a context.
            // The 'test' profile supplies the in-memory database that can, which is why gate G3 is
            // asserted under this profile and not the default one.
            assertThat(context.getEnvironment().getActiveProfiles())
                    .as("exactly one profile, so no second profile can be quietly contributing beans")
                    .containsExactly("test");
        }

        @Test
        @DisplayName("the entry point is itself a bean, registered exactly once under its published name")
        void theEntryPointIsRegisteredOnce() {
            assertThat(context.getBeanNamesForType(CardDemoApplication.class))
                    .as("the configuration class this context was refreshed from is a bean in it")
                    .containsExactly("cardDemoApplication");
            assertThat(context.getBean(CardDemoApplication.class)).isNotNull();

            // Through getUserClass, because a @Configuration class may or may not be enhanced with a
            // generated subclass depending on whether it declares @Bean methods - a framework
            // implementation detail this assertion has no business being sensitive to.
            assertThat(ClassUtils.getUserClass(context.getBean(CardDemoApplication.class).getClass())
                    .getName())
                    .as("the published fully-qualified name, which app/java/pom.xml carries as a "
                            + "string and the repackaged jar carries as its Start-Class")
                    .isEqualTo(PUBLISHED_FQN);
        }
    }

    @Nested
    @DisplayName("The online surface - 17 screen controllers, and the one endpoint that replaces Boot's")
    class TheOnlineSurface {

        @Test
        @DisplayName("every one of the 17 CICS online programs has exactly one controller bean")
        void allSeventeenScreenControllersResolve() {
            Map<String, List<String>> byType = beansByUserClassName(
                    context.getBeanNamesForAnnotation(RestController.class));

            assertThat(byType.keySet())
                    .as("one controller per online program; a missing entry means a program lost its "
                            + "REST surface")
                    .containsAll(SCREEN_CONTROLLERS);
            SCREEN_CONTROLLERS.forEach(controller ->
                    assertThat(byType.get(controller))
                            .as("%s must be registered exactly once - two definitions of the same "
                                    + "controller would publish its routes twice", controller)
                            .hasSize(1));
        }

        @Test
        @DisplayName("the controller inventory is exactly those 17 plus the container error endpoint")
        void theControllerInventoryIsClosed() {
            List<String> expected = new ArrayList<>(SCREEN_CONTROLLERS);
            expected.add(WebConfig.CobolErrorEndpoint.class.getName());

            // Two-sided, and the second side is the point. A "contains" assertion would pass over an
            // eighteenth screen controller - an invented program, or a test fixture that drifted into
            // a scanned package - and one such fixture really did join this context once, publishing
            // ten routes the deployed artifact does not have. Naming the error endpoint explicitly is
            // what lets the count stay closed while still permitting the one non-screen controller the
            // module legitimately publishes.
            assertThat(beansByUserClassName(context.getBeanNamesForAnnotation(RestController.class))
                    .keySet())
                    .as("the whole online surface: 17 screens, plus the endpoint that replaces Spring "
                            + "Boot's BasicErrorController rather than adding to it")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("the online surface is 17 screens - the CSD's 18 programs less the one with no source")
        void theOnlineSurfaceIsSeventeenScreens() {
            // app/csd/CARDDEMO.CSD defines 18 programs and 18 transactions, and the eighteenth -
            // COCRDSEC, "CREDIT CARD SEARCH", reached by transaction CDV1 - has no file in app/cbl and
            // no reference in any COBOL source anywhere. The CSD count is 18; the migratable source
            // count is 17. A Java type for the difference would be an invented program rather than a
            // translated one, which is what gate G14 forbids, and setting the container error endpoint
            // aside is what makes the remaining figure directly comparable to the CSD's.
            assertThat(SCREEN_CONTROLLERS)
                    .as("one entry per CICS online program that actually has source")
                    .hasSize(17)
                    .doesNotHaveDuplicates();

            List<String> screens = new ArrayList<>(beansByUserClassName(
                    context.getBeanNamesForAnnotation(RestController.class)).keySet());
            screens.remove(WebConfig.CobolErrorEndpoint.class.getName());

            assertThat(screens)
                    .as("seventeen screen controllers and not an eighteenth")
                    .hasSize(17)
                    .containsExactlyInAnyOrderElementsOf(SCREEN_CONTROLLERS);
        }

        @Test
        @DisplayName("@RestController and @Controller select the same beans, so none is MVC-only")
        void everyControllerIsAJsonController() {
            // @RestController is @Controller plus @ResponseBody. If the two selections ever differed,
            // a controller would be rendering through a view resolver instead of writing a body - and
            // this module has no view layer at all, so the response would be a whitelabel page rather
            // than the screen payload.
            assertThat(beansByUserClassName(context.getBeanNamesForAnnotation(Controller.class))
                    .keySet())
                    .containsExactlyInAnyOrderElementsOf(beansByUserClassName(
                            context.getBeanNamesForAnnotation(RestController.class)).keySet());
        }

        @Test
        @DisplayName("the report screen's job-submission port is wired, replacing the CICS TDQ write")
        void theJobSubmissionPortIsWired() {
            // CORPT00C writes 80-byte JCL skeletons to transient data queue JOBS. Java has no TDQ, so
            // the write became a port whose default implementation is a component nested inside the
            // controller that submits through it. It is asserted here because an unwired port fails
            // only at the moment a report is requested, and no context-free test reaches that.
            String portBeanName = "reportRequestController.InternalReaderJobSubmissionPort";

            assertThat(context.containsBean(portBeanName))
                    .as("the default job-submission port must be a bean, not merely a nested class")
                    .isTrue();
            Class<?> portType = ClassUtils.getUserClass(context.getType(portBeanName));
            assertThat(portType.getName())
                    .isEqualTo(ROOT_PACKAGE
                            + ".transaction.ReportRequestController$InternalReaderJobSubmissionPort");
            assertThat(portType.getInterfaces())
                    .extracting(Class::getName)
                    .as("the implementation is injected through the port interface, so the writer can "
                            + "be substituted without touching the controller")
                    .contains(ROOT_PACKAGE + ".transaction.ReportRequestController$JobSubmissionPort");
        }
    }

    @Nested
    @DisplayName("The batch surface - 9 jobs, their configuration beans, and the two subprograms that "
            + "are not jobs")
    class TheBatchSurface {

        @Test
        @DisplayName("exactly the nine jobs are published, under the names a submission looks up")
        void exactlyTheNineJobsArePublished() {
            assertThat(context.getBeanNamesForType(Job.class))
                    .as("eight programs invoked by EXEC PGM= in app/jcl and app/proc, plus the "
                            + "JCL-orphaned CBTRN01C - nine, and nothing else")
                    .containsExactlyInAnyOrderElementsOf(JOB_BEAN_NAMES);
        }

        @Test
        @DisplayName("each job's own name equals its bean name, so a submission by name resolves it")
        void eachJobNameMatchesItsBeanName() {
            // A submission is resolved by bean name and then run by job name, and Spring Batch keys a
            // job's execution history by the job's OWN name. If the two ever diverged, the job would
            // launch and its history would accumulate under a name nobody queries.
            JOB_BEAN_NAMES.forEach(jobName ->
                    assertThat(context.getBean(jobName, Job.class).getName())
                            .as("bean '%s' must publish itself under that same name", jobName)
                            .isEqualTo(jobName));
        }

        @Test
        @DisplayName("each job's configuration bean exists under a name that cannot collide with it")
        void eachJobConfigurationBeanIsNamedApart() {
            JOB_CONFIGURATION_BEAN_NAMES.forEach(configurationBeanName -> {
                assertThat(context.containsBean(configurationBeanName))
                        .as("the @Configuration class that publishes a job is itself a bean")
                        .isTrue();
                assertThat(JOB_BEAN_NAMES)
                        .as("a configuration bean named after its own job collides with the job bean, "
                                + "and Spring Boot refuses the context rather than picking a winner")
                        .doesNotContain(configurationBeanName);
            });
            assertThat(JOB_CONFIGURATION_BEAN_NAMES).hasSameSizeAs(JOB_BEAN_NAMES);
        }

        @Test
        @DisplayName("the JCL-orphaned posting job is registered and runnable, with nothing to trigger it")
        void theOrphanJobIsRegistered() {
            // CBTRN01C is invoked by no JCL anywhere in the repository, yet it is one of the 28
            // in-scope programs, so it migrates as a fully runnable job with no schedule (gate G13).
            // Deleting it or wiring it into a pipeline would both be behaviour changes.
            assertThat(context.getBeanNamesForType(Job.class)).contains("transactionPostingJob");
            assertThat(context.getBean("transactionPostingJob", Job.class).getName())
                    .isEqualTo("transactionPostingJob");
        }

        @Test
        @DisplayName("neither called subprogram is a Job, despite a mandated name that ends in 'Job'")
        void neitherSubprogramIsAJob() {
            // Gate G12. CBSTM03B is CBSTM03A's data-access collaborator, called at 13 sites, and
            // CSUTLDTC is a date-validation subprogram called from two ONLINE programs. Neither has an
            // EXEC PGM= anywhere, so neither is a job; the names came from the build prompt and the
            // behaviour comes from the source.
            SUBPROGRAM_BEAN_NAMES.forEach(beanName -> {
                assertThat(context.containsBean(beanName))
                        .as("%s is an injected collaborator and must be in the context", beanName)
                        .isTrue();
                assertThat(context.getBean(beanName))
                        .as("%s must NOT be a Spring Batch Job", beanName)
                        .isNotInstanceOf(Job.class);
                assertThat(context.getBeanNamesForType(Job.class)).doesNotContain(beanName);
            });
        }

        @Test
        @DisplayName("the declared job-contract graph covers exactly the nine published jobs")
        void theContractGraphCoversExactlyThePublishedJobs() {
            // application.yml declares one carddemo.jobs entry per job, keyed in kebab-case, and
            // BatchConfig validates that graph at refresh. Comparing the declared keys against the
            // published job beans is what proves the property document and the bean graph agree: a
            // tenth contract, or a job with no contract, is a startup-time defect that no
            // context-free test can surface.
            BatchConfig batchConfig = context.getBean(BatchConfig.class);
            List<String> declaredJobBeanNames = batchConfig.jobContracts().keySet().stream()
                    .map(CardDemoApplicationTest::jobBeanNameOf)
                    .toList();

            assertThat(declaredJobBeanNames)
                    .as("one declared contract per published job, and no contract for a job that "
                            + "does not exist")
                    .containsExactlyInAnyOrderElementsOf(JOB_BEAN_NAMES);
            assertThat(batchConfig.jobContracts().keySet())
                    .as("nine keys, so a tenth declared job would fail here as well as in the graph")
                    .hasSameSizeAs(JOB_BEAN_NAMES);
        }

        @Test
        @DisplayName("each declared contract names a distinct COBOL program, so no key is re-pointed")
        void eachContractNamesADistinctProgram() {
            BatchConfig batchConfig = context.getBean(BatchConfig.class);
            List<String> programs = batchConfig.jobContracts().keySet().stream()
                    .map(key -> batchConfig.contract(key).program())
                    .toList();

            assertThat(programs)
                    .as("nine jobs, nine distinct PROGRAM-IDs; two keys pointing at one program would "
                            + "mean a program was translated twice and another not at all")
                    .doesNotHaveDuplicates()
                    .hasSameSizeAs(JOB_BEAN_NAMES)
                    .allSatisfy(program -> assertThat(program).isNotBlank());
        }
    }

    @Nested
    @DisplayName("The data-access surface - 10 repositories, 4 writers, 1 parameter reader")
    class TheDataAccessSurface {

        @Test
        @DisplayName("exactly the ten dataset repositories are registered, one per base dataset")
        void exactlyTheTenRepositoriesAreRegistered() {
            Map<String, List<String>> byType = beansByUserClassName(
                    context.getBeanNamesForAnnotation(Repository.class));

            // Two-sided again: an eleventh @Repository would mean a dataset acquired a second access
            // path, and the two alternate indexes are finder methods on their base repositories rather
            // than repositories of their own (gate G45).
            assertThat(byType.keySet())
                    .as("one repository per base dataset - never a second one for an alternate index")
                    .containsExactlyInAnyOrderElementsOf(DATASET_REPOSITORIES);
            DATASET_REPOSITORIES.forEach(repository ->
                    assertThat(byType.get(repository))
                            .as("%s must be registered exactly once", repository)
                            .hasSize(1));
        }

        @Test
        @DisplayName("the four output writers and the DATEPARM reader are all wired")
        void theWritersAndTheParameterReaderAreWired() {
            DATASET_WRITERS_AND_READERS.forEach(type -> {
                assertThat(typeIsOnClasspath(type))
                        .as("%s must exist as a type before it can be a bean", type)
                        .isTrue();
                assertThat(beanNamesOfType(type))
                        .as("%s must be registered exactly once", type)
                        .hasSize(1);
            });
        }

        @Test
        @DisplayName("no repository exists for DISCGRP or TRNXFILE, whose access lives with its reader")
        void theTwoLogicalRepositoriesHaveNoTypeOfTheirOwn() {
            // The plan counts twelve repositories, and that is a count of dataset access paths rather
            // than of files. DISCGRP is read by the interest job's own access collaborator, and
            // TRNXFILE is owned by StatementGenerationJobB - faithful to CBSTM03B.CBL, which declares
            // all four of the statement job's input files itself. Asserting the absence is what stops
            // a later reader "restoring" a repository the module never had.
            ABSENT_REPOSITORY_TYPES.forEach(type ->
                    assertThat(typeIsOnClasspath(type))
                            .as("%s does not exist: the plan's repository count is logical, not a file "
                                    + "count", type)
                            .isFalse());
        }

        @Test
        @DisplayName("exactly one DataSource is wired, and no dataset name is hard-coded into Java")
        void exactlyOneDataSourceIsWired() {
            assertThat(context.getBeanNamesForType(DataSource.class))
                    .as("one pooled DataSource; under this profile it is the in-memory database, and "
                            + "in production it is the deployment-supplied driver")
                    .containsExactly("dataSource");

            // Gate G46, asserted where it is observable: every dataset name is resolved from
            // configuration, so the environment - not any Java source - is what holds them.
            assertThat(context.getEnvironment().getProperty("carddemo.datasets.ACCTDAT.dsname"))
                    .as("the account master's dataset name is bound from configuration")
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("The configuration surface - the four config classes and the beans they publish")
    class TheConfigurationSurface {

        @Test
        @DisplayName("all four config classes are registered, each exactly once")
        void allFourConfigurationClassesAreRegistered() {
            // These four are this file's declared dependencies, so they are asserted as compile-checked
            // types rather than as names: a rename would fail the build here rather than at test time.
            assertThat(context.getBeanNamesForType(DataSourceConfig.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(BatchConfig.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(WebConfig.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(CobolCharsetConfig.class)).hasSize(1);
        }

        @Test
        @DisplayName("exactly one Clock bean exists, so every date-bearing screen has its instant source")
        void exactlyOneClockIsPublished() {
            // common/DateHeader is the Java form of the WS-DATE-TIME group in CSDAT01Y, which supplies
            // the CURDATE and CURTIME fields in the top-right corner of all 17 screens. It never calls
            // now() of its own accord: it takes a Clock and reads it once, so a test can pass
            // Clock.fixed(...) and assert an exact header. That design needs exactly one Clock in the
            // context, and its absence would break every date-bearing controller at startup.
            assertThat(context.getBeanNamesForType(Clock.class)).containsExactly("clock");

            Clock clock = context.getBean(Clock.class);
            assertThat(clock).isNotNull();

            // The zone, never an instant. Asserting a rendered time here would make this suite depend
            // on the system clock (practice B7); asserting the zone rule is deterministic and is the
            // property that matters - a fixed clock in a production context would freeze the date on
            // every screen.
            assertThat(clock.getZone())
                    .as("the production clock follows the platform zone and is never a fixed instant")
                    .isEqualTo(ZoneId.systemDefault());
        }

        @Test
        @DisplayName("the three charsets are named beans, so no dataset is decoded by platform default")
        void theCharsetsAreNamedBeans() {
            // Practice B8, and the classic silent corrupter of mainframe data. Every charset in this
            // module is named explicitly - the EBCDIC code page, the ASCII code page of the nine text
            // fixtures, and the one the dataset layer actually uses - so nothing falls back to the
            // platform default.
            assertThat(context.getBeanNamesForType(Charset.class))
                    .containsExactlyInAnyOrder(
                            "carddemoEbcdicCharset",
                            "carddemoAsciiCharset",
                            "carddemoDatasetCharset");
        }
    }

    @Nested
    @DisplayName("The batch infrastructure - a DataSource-backed JobRepository and one transaction "
            + "manager")
    class TheBatchInfrastructure {

        @Test
        @DisplayName("exactly one JobRepository is wired, which is what Spring Batch 5 requires to start")
        void exactlyOneJobRepositoryIsWired() {
            // Spring Batch 5 requires a DataSource-backed JobRepository: there is no in-memory
            // implementation to fall back on, so a batch context simply does not start without one. The
            // in-memory database supplied by the 'test' profile is what satisfies it here, and the
            // deployment-supplied driver is what satisfies it in production.
            assertThat(context.getBeanNamesForType(JobRepository.class)).containsExactly("jobRepository");
            assertThat(context.getBean(JobRepository.class)).isNotNull();
        }

        @Test
        @DisplayName("the JobRepository really queries its database, so it is backed rather than stubbed")
        void theJobRepositoryQueriesItsDatabase() {
            // A read, not a launch (gate G51 keeps job execution out of this suite). It is the one way
            // to distinguish "a JobRepository bean exists" from "a JobRepository whose schema is
            // present and reachable": the query round-trips to the database and answers null for a job
            // that has never run. That answer is also the proof required below that nothing was
            // launched at startup.
            JobRepository jobRepository = context.getBean(JobRepository.class);

            JOB_BEAN_NAMES.forEach(jobName -> {
                assertThat(jobRepository.isJobInstanceExists(jobName, new JobParameters()))
                        .as("job '%s' must have no instance, because nothing launched it", jobName)
                        .isFalse();
                assertThat(jobRepository.getLastJobExecution(jobName, new JobParameters()))
                        .as("job '%s' must have no execution history in a freshly refreshed context",
                                jobName)
                        .isNull();
            });
        }

        @Test
        @DisplayName("exactly one PlatformTransactionManager is wired, the one BatchConfig publishes")
        void exactlyOneTransactionManagerIsWired() {
            // One transaction manager, over the same DataSource the JobRepository uses. Two would make
            // a step's business work and its own metadata update commit under different managers, which
            // is how a step can be recorded as complete while its writes are rolled back.
            assertThat(context.getBeanNamesForType(PlatformTransactionManager.class))
                    .containsExactly("transactionManager");
            assertThat(context.getBean(PlatformTransactionManager.class)).isNotNull();
        }

        @Test
        @DisplayName("the batch step beans are registered for the jobs that declare them")
        void theDeclaredStepBeansAreRegistered() {
            // Only the steps that are published as beans in their own right, which is not every step
            // in the module: a job may build its steps inline. What matters here is that the ones
            // declared as beans resolve, because an unresolvable step definition fails the context.
            assertThat(context.getBeanNamesForType(Step.class))
                    .as("every published step must be a real bean")
                    .isNotEmpty()
                    .allSatisfy(stepBeanName ->
                            assertThat(context.getBean(stepBeanName, Step.class)).isNotNull());
        }
    }

    @Nested
    @DisplayName("Nothing runs at startup - the jobs are registered and untriggered")
    class NothingRunsAtStartup {

        @Test
        @DisplayName("spring.batch.job.enabled is false, so Boot launches no job on refresh")
        void batchJobExecutionIsDisabled() {
            assertThat(context.getEnvironment().getProperty("spring.batch.job.enabled"))
                    .as("application.yml sets it false and the 'test' profile restates it; jobs are "
                            + "launched explicitly, exactly as JCL submits one EXEC PGM= step at a time")
                    .isEqualTo("false");
        }

        @Test
        @DisplayName("no runner of any kind is registered, so a refresh starts no work")
        void noRunnerIsRegistered() {
            // Two mechanisms could launch something at startup and neither is present. Spring Boot's
            // own JobLauncherApplicationRunner is conditional on spring.batch.job.enabled being true,
            // and the module's JCL launcher is conditional on a submission naming a job. Asserting the
            // ApplicationRunner and CommandLineRunner types rather than either implementation covers
            // any third mechanism a later change might introduce.
            assertThat(context.getBeanNamesForType(ApplicationRunner.class))
                    .as("an ApplicationRunner would run during the refresh this suite performs")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(CommandLineRunner.class)).isEmpty();
            assertThat(context.getEnvironment().getProperty("carddemo.batch.job-name"))
                    .as("no submission is being made, so the JCL launcher must not even exist")
                    .isNull();
        }

        @Test
        @DisplayName("no job has an execution, so the orphan posting job stays runnable and untriggered")
        void noJobHasExecuted() {
            // The observable form of gate G13. JobExplorer reports the jobs the repository has seen
            // execute; an empty answer in a context holding nine job beans is exactly the required
            // state - registered, launchable, and launched by nothing.
            assertThat(context.getBean(JobExplorer.class).getJobNames())
                    .as("nine jobs are registered and none of them has run")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(Job.class))
                    .as("registration is unaffected by never having run")
                    .hasSize(JOB_BEAN_NAMES.size());
        }
    }

    @Nested
    @DisplayName("The excluded-technology boundary - what the closed dependency set keeps out")
    class TheExcludedTechnologyBoundary {

        @Test
        @DisplayName("no excluded technology is even on the classpath, let alone in the graph")
        void noExcludedTechnologyIsOnTheClasspath() {
            // Asserted at the classpath rather than at the bean graph, because that is where the
            // decision was actually made: app/java/pom.xml declares six dependencies and none of these
            // families is among them. A bean-only check would pass while an unused jar sat on the
            // classpath waiting for auto-configuration to notice it.
            EXCLUDED_TECHNOLOGY_TYPES.forEach((family, type) ->
                    assertThat(typeIsOnClasspath(type))
                            .as("%s is excluded by the plan's closed dependency set, so %s must not be "
                                    + "resolvable", family, type)
                            .isFalse());
        }

        @Test
        @DisplayName("no bean comes from a security, persistence or schema-migration package")
        void noBeanComesFromAnExcludedPackage() {
            // The second half of the same boundary, stated over the graph so that a family reaching the
            // classpath transitively still cannot contribute a bean unnoticed. Authentication stays
            // file-based against USRSEC with the plaintext comparison COSGN00C performs (gate G41), and
            // there is no ORM, no entity manager and no migration tool anywhere (gate G44).
            List<String> offenders = new ArrayList<>();
            for (String beanName : context.getBeanDefinitionNames()) {
                Class<?> type = context.getType(beanName);
                if (type == null) {
                    continue;
                }
                String packageName = ClassUtils.getUserClass(type).getPackageName();
                if (EXCLUDED_BEAN_PACKAGE_PREFIXES.stream().anyMatch(packageName::startsWith)) {
                    offenders.add(beanName + " (" + type.getName() + ")");
                }
            }

            assertThat(offenders)
                    .as("no bean may come from an excluded technology family")
                    .isEmpty();
        }

        @Test
        @DisplayName("no metrics or observation registry bean exists, so nothing is ever collected")
        void noObservabilityRegistryIsWired() {
            // This is the one exclusion whose letter cannot fully hold, and it is asserted honestly
            // rather than falsely. Three Micrometer jars ARE on the classpath - observation, commons and
            // core - because they are irreducible transitives of spring-boot-starter-web and
            // spring-boot-starter-batch, both mandated by the plan: AbstractJob and AbstractStep, the
            // base classes of every Spring Batch job and step, declare fields and public setters typed
            // on these two registry interfaces, so excluding them would stop the module loading rather
            // than slim it. app/java/pom.xml records that conflict in full.
            //
            // What the exclusion actually asks for is that no telemetry is collected or exported, and
            // that is exactly what is asserted: no registry bean of either kind, no Actuator, no
            // exporter, nothing to scrape. The assertion is deliberately one-directional - it holds
            // whether those jars are on the classpath or not - so that a future build which does manage
            // to keep them out passes here rather than failing for having improved.
            ABSENT_REGISTRY_TYPES.forEach(registryType ->
                    assertThat(beanNamesOfType(registryType))
                            .as("%s must have no bean: nothing is collected, aggregated or exported",
                                    registryType)
                            .isEmpty());
        }

        @Test
        @DisplayName("the excluded observability surface is exactly the three irreducible jars, and no more")
        void theObservabilitySurfaceIsBoundedToWhatCannotBeRemoved() {
            // The bean-graph assertion above proves nothing is collected. This proves the graph itself is
            // cut back to the artifacts that genuinely cannot leave it, which is the part a comment
            // cannot keep true: a starter's transitive set shifts under a version bump, and an excluded
            // family creeps back in without anybody declaring it.
            //
            // First prove the instrument. Surefire runs this suite through a manifest-only booter jar, so
            // an enumeration that saw one entry and stopped would make every absence assertion below
            // pass over an empty search - the worst kind of green.
            List<String> classpathJars = runtimeClasspathJarNames();
            assertThat(classpathJars)
                    .as("the classpath enumeration must see the whole test classpath, not just the "
                            + "surefire booter jar, or the absence assertions below prove nothing")
                    .hasSizeGreaterThanOrEqualTo(CLASSPATH_PROBE_FLOOR);
            CLASSPATH_PROBE_ANCHORS.forEach(anchor ->
                    assertThat(classpathJars)
                            .as("the enumeration must find %s, which is known to be on this classpath",
                                    anchor)
                            .anyMatch(jar -> jar.startsWith(anchor)));

            // The two removable members of the excluded surface are excluded in app/java/pom.xml, so
            // they must be gone - checked by jar name and again by type resolution, because the two
            // techniques fail in opposite directions.
            REMOVED_OBSERVABILITY_JAR_PREFIXES.forEach(removed ->
                    assertThat(classpathJars)
                            .as("%s* is excluded from spring-boot-starter-batch in app/java/pom.xml and "
                                    + "must not be back on the classpath", removed)
                            .noneMatch(jar -> jar.startsWith(removed)));
            REMOVED_OBSERVABILITY_TYPES.forEach(removedType ->
                    assertThat(typeIsOnClasspath(removedType))
                            .as("%s belongs to an excluded artifact this module removes, so it must not "
                                    + "resolve", removedType)
                            .isFalse());

            // Nothing that could collect, export or trace may be present at all - this is the half of
            // AAP 0.5.6 that IS fully achievable, so it is asserted as an absolute.
            ABSENT_OBSERVABILITY_TYPES.forEach((family, type) ->
                    assertThat(typeIsOnClasspath(type))
                            .as("%s is excluded by AAP 0.5.6, so %s must not be resolvable",
                                    family, type)
                            .isFalse());

            // Finally the upper bound: the Micrometer footprint is exactly the three irreducible API
            // jars. A fourth one fails here rather than being discovered in a dependency tree later.
            List<String> micrometerJars = classpathJars.stream()
                    .filter(jar -> jar.startsWith("micrometer-"))
                    .sorted()
                    .toList();
            assertThat(micrometerJars)
                    .as("only the irreducible Micrometer API jars may be present: %s",
                            IRREDUCIBLE_OBSERVABILITY_JAR_PREFIXES)
                    .hasSameSizeAs(IRREDUCIBLE_OBSERVABILITY_JAR_PREFIXES)
                    .allMatch(jar -> IRREDUCIBLE_OBSERVABILITY_JAR_PREFIXES.stream()
                            .anyMatch(jar::startsWith));
            IRREDUCIBLE_OBSERVABILITY_JAR_PREFIXES.forEach(irreducible ->
                    assertThat(micrometerJars)
                            .as("%s* is irreducible under the mandated starters, so its absence would "
                                    + "mean the module can no longer load a job or serve a request",
                                    irreducible)
                            .anyMatch(jar -> jar.startsWith(irreducible)));
        }

        @Test
        @DisplayName("no schema is created for the application's own data")
        void noApplicationSchemaIsCreated() {
            // Gate G44 from the configuration side. The batch metadata schema is initialised only
            // against the throwaway in-memory database used by tests, and script-based initialisation
            // is switched off outright, so no schema.sql or data.sql arriving on the classpath can
            // quietly create an application table.
            assertThat(context.getEnvironment().getProperty("spring.sql.init.mode"))
                    .as("no schema.sql and no data.sql may be executed against the DataSource")
                    .isEqualTo("never");
        }
    }

    @Nested
    @DisplayName("Component scope is the shipped graph, not the test tree's")
    class ComponentScopeIsTheShippedGraph {

        @Test
        @DisplayName("every scanned package contributes at least one bean, so none fell out of scope")
        void everyScannedPackageContributesABean() {
            // The end-to-end form of the scan-list check. A dropped entry in scanBasePackages removes a
            // whole domain package from component scope, and the first symptom is otherwise a missing
            // bean at runtime. Stated as a minimum rather than an exact count on purpose: an exact
            // count would break every time a package legitimately gained a bean.
            Map<String, Long> beansPerPackage = new LinkedHashMap<>();
            for (String scannedPackage : EXPECTED_SCAN_PACKAGES) {
                beansPerPackage.put(scannedPackage, beanCountUnder(scannedPackage));
            }

            assertThat(beansPerPackage)
                    .as("all eleven declared packages must be represented in the graph")
                    .hasSize(EXPECTED_SCAN_PACKAGES.size())
                    .allSatisfy((scannedPackage, beanCount) -> assertThat(beanCount)
                            .as("package %s holds no bean at all, which means either it left component "
                                    + "scope or it should never have been in the scan list",
                                    scannedPackage)
                            .isPositive());
        }

        @Test
        @DisplayName("no bean comes from the test-support package, which is deliberately unscanned")
        void noBeanComesFromTheTestSupportPackage() {
            assertThat(beanCountUnder(TEST_SUPPORT_PACKAGE))
                    .as("com.vsergeychik.carddemo.testsupport exists precisely to be out of component "
                            + "scope; scanning it would put its fixtures into the container")
                    .isZero();
            assertThat(scanBasePackages())
                    .as("and the scan list must not grow to cover it")
                    .doesNotContain(TEST_SUPPORT_PACKAGE);
        }

        @Test
        @DisplayName("every bean of this module is loaded from the main output, never from test-classes")
        void everyBeanIsLoadedFromTheMainOutput() {
            // The graph must be the graph the artifact ships. src/test/java compiles to
            // target/test-classes, which is on the classpath of every test run and of a local run
            // started from this module's build output, so a stereotyped class there is a scan candidate
            // whose bean would exist in a developer's run and not in production.
            List<String> offenders = new ArrayList<>();
            for (String beanName : context.getBeanDefinitionNames()) {
                Class<?> type = context.getType(beanName);
                if (type == null) {
                    continue;
                }
                Class<?> userType = ClassUtils.getUserClass(type);
                if (!userType.getPackageName().startsWith(ROOT_PACKAGE)) {
                    continue;
                }
                // Generated and hidden classes - lambdas registered as beans, and framework-generated
                // subclasses - have no class file to locate, so there is nothing to attribute and
                // nothing to judge. The class they were generated from is judged on its own account.
                URL location =
                        userType.getResource("/" + userType.getName().replace('.', '/') + CLASS_SUFFIX);
                if (location == null) {
                    continue;
                }
                if (location.toString().contains("test-classes")) {
                    offenders.add(beanName + " (" + userType.getName() + ")");
                }
            }

            assertThat(offenders)
                    .as("a bean compiled from the test tree is in the container but not in the "
                            + "artifact; put stereotyped fixtures in "
                            + "com.vsergeychik.carddemo.testsupport, which is deliberately unscanned")
                    .isEmpty();
        }

        /**
         * The one defect in this area that a started context structurally cannot detect.
         *
         * <p>Spring Boot's test framework contributes a {@code TypeExcludeFilter} that excludes test
         * classes and everything they enclose, so a controller-shaped fixture inside a scanned package
         * is filtered out of exactly the contexts a test could assert on - and registered in the plain
         * {@code main} run nobody asserts on. That is not hypothetical: a {@code @RestController}
         * fixture nested in a {@code config} suite once joined the graph as an extra controller
         * publishing ten routes the deployed artifact does not have, some of which abend by design.
         *
         * <p>So this reads the compiled test output directly - no context, no filter, no class
         * loading - and matches the annotation descriptors in the class files as bytes. The output root
         * comes from this suite's own code source rather than from a repository-relative path, so the
         * check follows the build instead of a convention about where the build puts things.
         *
         * @throws IOException if the compiled test output cannot be walked
         */
        @Test
        @DisplayName("no class compiled from the test tree inside a scanned package is a controller")
        void noTestTreeClassInsideAScannedPackageIsAController() throws IOException {
            List<String> offenders = new ArrayList<>();
            Path testOutput = compiledTestOutput();
            try (Stream<Path> files = Files.walk(testOutput)) {
                for (Path file : files.filter(path -> path.toString().endsWith(CLASS_SUFFIX)).toList()) {
                    String className = classNameOf(testOutput, file);
                    if (liesInAScannedPackage(className) && declaresAControllerStereotype(file)) {
                        offenders.add(className);
                    }
                }
            }

            assertThat(offenders)
                    .as("a controller-shaped test fixture inside a scanned package is offered to every "
                            + "context refreshed from target/test-classes, including a local run of the "
                            + "application; put it in com.vsergeychik.carddemo.testsupport")
                    .isEmpty();
        }

        /**
         * Reports whether a class file declares {@code @Controller} or an annotation meta-annotated
         * with it, {@code @RestController} being the one this module's fixtures used.
         *
         * <p>The class file is read as bytes and the annotation descriptors are matched as text, so
         * nothing is loaded and no static initialiser of a test class runs during this check.
         * ISO-8859-1 is named explicitly because it is the one charset that round-trips arbitrary bytes
         * to characters without loss - this is a byte scan, not text (practice B8).
         *
         * @param classFile the compiled class to inspect
         * @return {@code true} when the class declares a controller stereotype
         * @throws IOException if the class file cannot be read
         */
        private boolean declaresAControllerStereotype(Path classFile) throws IOException {
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return bytes.contains(CONTROLLER_DESCRIPTOR) || bytes.contains(REST_CONTROLLER_DESCRIPTOR);
        }

        /**
         * Reports whether a class lies beneath one of the eleven packages the entry point scans.
         *
         * @param className the fully-qualified class name, with {@code $} for nesting
         * @return {@code true} when component scanning would reach it
         */
        private boolean liesInAScannedPackage(String className) {
            return scanBasePackages().stream()
                    .anyMatch(scanned -> className.startsWith(scanned + "."));
        }

        /**
         * Derives a class name from the path of its class file, relative to the output root.
         *
         * @param root the compiled output root
         * @param classFile the class file beneath it
         * @return the fully-qualified class name, with {@code $} retained for nested classes
         */
        private String classNameOf(Path root, Path classFile) {
            String relative = root.relativize(classFile).toString();
            return relative.substring(0, relative.length() - CLASS_SUFFIX.length())
                    .replace(File.separatorChar, '.');
        }

        /**
         * The directory this suite's own class file was loaded from, which is the compiled test tree.
         *
         * @return the compiled test output root
         */
        private Path compiledTestOutput() {
            Path location = Path.of(URI.create(CardDemoApplicationTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toString()));
            assertThat(Files.isDirectory(location))
                    .as("this suite runs from a directory of class files, which is what makes the test "
                            + "tree walkable; a packaged test jar would need a different reader")
                    .isTrue();
            return location;
        }
    }

    @Nested
    @DisplayName("The published identity and the declarations that produce the graph above")
    class ThePublishedIdentity {

        @Test
        @DisplayName("@SpringBootApplication is present, and is the only annotation on the class")
        void springBootApplicationIsTheSoleAnnotation() {
            // Two-sided, and the second side is what makes it worth asserting. Spring Batch's own
            // enablement annotation is the natural thing for someone to add here, and under Spring
            // Boot 3 adding it DISABLES batch auto-configuration rather than enabling it - the nine job
            // beans would simply cease to exist. A sole-annotation assertion refuses that, and every
            // other well-meant addition, in one line.
            Annotation[] declared = CardDemoApplication.class.getDeclaredAnnotations();

            assertThat(declared).hasSize(1);
            assertThat(declared[0].annotationType()).isEqualTo(SpringBootApplication.class);
        }

        @Test
        @DisplayName("scanBasePackages names exactly the eleven packages, in architectural order")
        void scanBasePackagesAreExactlyTheElevenInOrder() {
            assertThat(scanBasePackages())
                    .as("order is the two foundation packages first, then the nine domain packages")
                    .containsExactlyElementsOf(EXPECTED_SCAN_PACKAGES)
                    .doesNotHaveDuplicates()
                    .allSatisfy(name -> assertThat(name).startsWith(ROOT_PACKAGE + "."));
        }

        @Test
        @DisplayName("app/java/pom.xml pins exactly this class, so the Boot plugin target resolves")
        void buildManifestPinsThisExactClass() throws IOException, ClassNotFoundException {
            // The one thing in this suite that no context can answer. Maven holds mainClass as a plain
            // STRING, entirely outside the compiler's symbol graph, so renaming or moving this class
            // leaves the build green and the repackaged jar unbootable - its Start-Class would name a
            // type that no longer exists.
            String pom = Files.readString(moduleDescriptor(), StandardCharsets.UTF_8);
            Matcher matcher = MAIN_CLASS.matcher(pom);

            assertThat(matcher.find())
                    .as("the module descriptor must configure a <mainClass> for "
                            + "spring-boot-maven-plugin")
                    .isTrue();
            String configured = matcher.group(1);

            assertThat(configured).isEqualTo(PUBLISHED_FQN);
            assertThat(Class.forName(configured))
                    .as("loading the configured value is the check: if it resolves, the jar's "
                            + "Start-Class names a real class")
                    .isSameAs(CardDemoApplication.class);
            assertThat(matcher.find())
                    .as("<mainClass> must be configured exactly once; a second one would make the "
                            + "effective entry point depend on plugin merge order")
                    .isFalse();
        }

        @Test
        @DisplayName("the jar is packaged so a deployment-supplied JDBC driver can be loaded into it")
        void theBuildPackagesALoadableArchive() throws IOException {
            // The module pins no JDBC driver coordinate by design (risk R-E), so the driver is never
            // inside carddemo.jar - and the default JAR layout names JarLauncher, which builds the
            // application class loader from BOOT-INF alone and ignores -cp entirely. That combination is
            // unshippable: every deployment would refuse to start with "the JDBC driver class is not on
            // the classpath" while the driver sat on the machine. ZIP names PropertiesLauncher instead,
            // which reads loader.path / LOADER_PATH. Asserted from the descriptor because Maven holds it
            // as a plain string outside the compiler's symbol graph, exactly as it holds mainClass.
            String pom = Files.readString(moduleDescriptor(), StandardCharsets.UTF_8);
            Matcher matcher = LAYOUT.matcher(pom);

            assertThat(matcher.find())
                    .as("spring-boot-maven-plugin must declare a repackage <layout>")
                    .isTrue();
            assertThat(matcher.group(1))
                    .as("ZIP is the layout whose launcher honours LOADER_PATH; the default JAR layout "
                            + "cannot load a driver that was not packaged")
                    .isEqualTo("ZIP");
            assertThat(matcher.find())
                    .as("<layout> must be configured exactly once")
                    .isFalse();
        }

        @Test
        @DisplayName("main is the only public method, and is public static void(String[])")
        void mainIsTheOnlyPublicMethod() throws NoSuchMethodException {
            assertThat(authored(CardDemoApplication.class.getDeclaredMethods()).stream()
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .toList())
                    .containsExactly("main");

            Method main = CardDemoApplication.class.getDeclaredMethod("main", String[].class);
            assertThat(Modifier.isPublic(main.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
            assertThat(main.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("the class declares no field at all, so it holds no state of any kind")
        void theClassDeclaresNoField() {
            // Gate G53 at its strongest. "No mutable static" would permit a constant, and a constant
            // here would be the beginning of configuration living in the composition root - which is
            // what application.yml exists for. A composition root needs no field, so it has none.
            assertThat(authored(CardDemoApplication.class.getDeclaredFields()).stream()
                    .map(Field::getName)
                    .toList())
                    .as("a composition root that needs no state should declare none")
                    .isEmpty();
        }

        @Test
        @DisplayName("only the implicit public no-arg constructor exists")
        void onlyTheImplicitConstructorExists() {
            List<Constructor<?>> constructors =
                    authored(CardDemoApplication.class.getDeclaredConstructors());

            assertThat(constructors).hasSize(1);
            assertThat(constructors.get(0).getParameterCount()).isZero();
            assertThat(Modifier.isPublic(constructors.get(0).getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the class is public, not final, and is no runner of any kind")
        void theClassIsAPlainConfigurationSource() {
            int modifiers = CardDemoApplication.class.getModifiers();

            assertThat(Modifier.isPublic(modifiers)).isTrue();
            assertThat(Modifier.isFinal(modifiers)).isFalse();
            assertThat(Modifier.isAbstract(modifiers)).isFalse();

            // A runner declared on the entry point would defeat spring.batch.job.enabled: false, and
            // the job translated from the JCL-orphaned CBTRN01C must stay runnable yet untriggered.
            assertThat(ApplicationRunner.class.isAssignableFrom(CardDemoApplication.class)).isFalse();
            assertThat(CommandLineRunner.class.isAssignableFrom(CardDemoApplication.class)).isFalse();
            assertThat(CardDemoApplication.class.getInterfaces()).isEmpty();
            assertThat(CardDemoApplication.class.getSuperclass()).isEqualTo(Object.class);
        }
    }

    @Nested
    @DisplayName("The launch mode - a JCL submission is a one-shot process, not a web application")
    class LaunchMode {

        /** A process with neither the system property nor the environment variable set. */
        private static final UnaryOperator<String> NOTHING_EXTERNAL = name -> null;

        /** The job name a submission would carry, and one this module really publishes. */
        private static final String A_PUBLISHED_JOB = "accountBalanceJob";

        /** The command-line form of the job-name property. */
        private static final String JOB_NAME_FLAG =
                "--" + BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY + "=";

        @Test
        @DisplayName("with no job name anywhere the online service starts, so the 17 screens are served")
        void noJobNameMeansTheOnlineService() {
            assertThat(CardDemoApplication.webApplicationTypeFor(new String[0], NOTHING_EXTERNAL))
                    .isEqualTo(WebApplicationType.SERVLET);
            assertThat(CardDemoApplication.webApplicationTypeFor(
                    new String[] {"--spring.profiles.active=test", "--server.port=0" },
                    NOTHING_EXTERNAL))
                    .as("an unrelated argument is not a submission")
                    .isEqualTo(WebApplicationType.SERVLET);
        }

        @Test
        @DisplayName("a job name on the command line starts a non-web process, so no port is bound for "
                + "work that has nothing to do with HTTP")
        void aJobNameOnTheCommandLineMeansAOneShotProcess() {
            assertThat(CardDemoApplication.webApplicationTypeFor(
                    new String[] {JOB_NAME_FLAG + A_PUBLISHED_JOB }, NOTHING_EXTERNAL))
                    .isEqualTo(WebApplicationType.NONE);
        }

        @Test
        @DisplayName("the flag counts even with an empty value: the submission must fail without having "
                + "bound a port")
        void aFlagWithNoValueStillCountsAsASubmission() {
            // BatchConfig.JclJobLauncher's constructor refuses a blank job name, so this invocation ends
            // in a startup failure either way. What is asserted is that it fails as a batch process:
            // starting a servlet container first would take the online surface up, bind 8080, and only
            // then refuse the job.
            assertThat(CardDemoApplication.webApplicationTypeFor(
                    new String[] {JOB_NAME_FLAG }, NOTHING_EXTERNAL))
                    .isEqualTo(WebApplicationType.NONE);
        }

        @Test
        @DisplayName("a job name supplied outside the command line counts too, which is how a container "
                + "or a scheduler passes it")
        void aJobNameSuppliedOutsideTheCommandLineCountsToo() {
            assertThat(CardDemoApplication.webApplicationTypeFor(new String[0],
                    name -> BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY.equals(name)
                            ? A_PUBLISHED_JOB
                            : null))
                    .isEqualTo(WebApplicationType.NONE);
        }

        @Test
        @DisplayName("a value exported empty outside the command line is not a submission, so an unset "
                + "variable cannot silently take the online service down")
        void aBlankValueSuppliedOutsideTheCommandLineIsNotASubmission() {
            assertThat(CardDemoApplication.webApplicationTypeFor(new String[0], name -> "   "))
                    .isEqualTo(WebApplicationType.SERVLET);
        }

        @Test
        @DisplayName("the property looked for is the launcher's own, so the two halves cannot disagree")
        void theDetectedPropertyIsTheLauncherOwn() {
            // Compile-checked rather than spelled out: BatchConfig.JclJobLauncher's own
            // @ConditionalOnProperty names this constant, so a rename moves both halves together.
            assertThat(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY)
                    .isEqualTo("carddemo.batch.job-name");
            assertThat(CardDemoApplication.isJclSubmission(
                    new String[] {"--" + BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY + "="
                            + A_PUBLISHED_JOB },
                    NOTHING_EXTERNAL))
                    .isTrue();
            assertThat(CardDemoApplication.isJclSubmission(new String[0], NOTHING_EXTERNAL)).isFalse();
        }

        @Test
        @DisplayName("the application carries the chosen mode, and nothing else is configured on it")
        void theApplicationCarriesTheChosenMode() {
            assertThat(CardDemoApplication
                    .springApplicationFor(new String[0], NOTHING_EXTERNAL)
                    .getWebApplicationType())
                    .isEqualTo(WebApplicationType.SERVLET);
            assertThat(CardDemoApplication
                    .springApplicationFor(new String[] {JOB_NAME_FLAG + A_PUBLISHED_JOB },
                            NOTHING_EXTERNAL)
                    .getWebApplicationType())
                    .isEqualTo(WebApplicationType.NONE);
        }

        @Test
        @DisplayName("the system property is preferred over the environment, which is Spring's own order")
        void theSystemPropertyIsPreferredOverTheEnvironment() {
            String name = BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY;
            assertThat(System.getenv(CardDemoApplication.environmentVariableFor(name)))
                    .as("this suite does not run with the submission variable exported")
                    .isNull();
            assertThat(CardDemoApplication.processValueOf(name))
                    .as("with neither source carrying it, nothing is resolved")
                    .isNull();

            System.setProperty(name, A_PUBLISHED_JOB);
            try {
                assertThat(CardDemoApplication.processValueOf(name)).isEqualTo(A_PUBLISHED_JOB);
            } finally {
                System.clearProperty(name);
            }
            assertThat(System.getProperty(name))
                    .as("the property is removed again, so no later test inherits a submission")
                    .isNull();
        }

        @Test
        @DisplayName("the environment spelling is the relaxed one Spring maps back to the canonical name")
        void theEnvironmentSpellingIsTheRelaxedOne() {
            assertThat(CardDemoApplication.environmentVariableFor(
                    BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY))
                    .isEqualTo("CARDDEMO_BATCH_JOB_NAME");
            assertThat(CardDemoApplication.environmentVariableFor("spring.profiles.active"))
                    .isEqualTo("SPRING_PROFILES_ACTIVE");
        }

        @Test
        @DisplayName("an explicit spring.main.web-application-type still wins, because Boot binds "
                + "spring.main.* after this setter has run")
        void anExplicitModeOverridesTheChoice() {
            // Asserted by starting one, because the claim is about Spring Boot's own ordering rather than
            // about this module's code. The subject is a bare @Configuration with no auto-configuration,
            // so nothing of the application graph is built: what is proved is that a SERVLET application
            // asked outright for none does not create a servlet context - and therefore binds no port.
            SpringApplication application = new SpringApplication(LaunchProbe.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);

            try (ConfigurableApplicationContext started = application.run(
                    "--spring.main.web-application-type=none",
                    "--spring.main.banner-mode=off")) {
                assertThat(started.getClass().getName())
                        .as("a servlet context would be an AnnotationConfigServletWebServerApplicationContext")
                        .doesNotContain("Servlet");
                assertThat(started.getEnvironment().getProperty("spring.main.web-application-type"))
                        .isEqualTo("none");
            }
        }
    }

    /**
     * The subject of the override proof: a configuration source with nothing in it.
     *
     * <p>Deliberately not {@link CardDemoApplication}. Starting the real application here would build
     * the whole graph a second time and need a {@code DataSource}; what the test is about is Spring
     * Boot's binding order, which any configuration source demonstrates. It carries no
     * {@code @EnableAutoConfiguration}, so no starter contributes anything, and it sits in the root
     * package, which {@code scanBasePackages} deliberately does not include - so it can never be
     * picked up by the application's own scan.
     */
    @Configuration
    static class LaunchProbe {
    }

    // =================================================================================================
    // Helpers. Every one of them is a pure query - nothing here mutates the context, registers a bean
    // definition or writes to the filesystem.
    // =================================================================================================

    /**
     * Groups bean names by the fully-qualified name of the class each of them was declared from.
     *
     * <p>Through {@link ClassUtils#getUserClass(Class)}, and that is essential rather than tidy: many
     * beans in this context are container-generated subclasses -
     * {@code UserAddController$$SpringCGLIB$$0} and every repository among them - so comparing raw bean
     * types against an expected inventory would fail on beans that are perfectly correct. The user class
     * is the class a reader wrote and the plan names.
     *
     * <p>A {@code List} per class rather than a single name, so a type accidentally registered twice is
     * visible as two entries instead of silently collapsing to one.
     *
     * @param beanNames the bean names to group; never {@code null}
     * @return name of declaring class to the bean names registered from it, in encounter order
     */
    private Map<String, List<String>> beansByUserClassName(String[] beanNames) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String beanName : beanNames) {
            Class<?> type = context.getType(beanName);
            if (type == null) {
                continue;
            }
            grouped.computeIfAbsent(ClassUtils.getUserClass(type).getName(),
                    key -> new ArrayList<>()).add(beanName);
        }
        return grouped;
    }

    /**
     * The bean names registered for a type named at runtime.
     *
     * <p>Named rather than imported because the domain types this suite inventories are not among this
     * file's declared dependencies; a type that is absent altogether yields an empty result, which the
     * caller asserts on explicitly rather than having it raised as an error here.
     *
     * @param typeName the fully-qualified type name; never {@code null}
     * @return the bean names of that type, or an empty list when the type is not on the classpath
     */
    private List<String> beanNamesOfType(String typeName) {
        if (!typeIsOnClasspath(typeName)) {
            return List.of();
        }
        try {
            return List.of(context.getBeanNamesForType(
                    Class.forName(typeName, false, getClass().getClassLoader())));
        } catch (ClassNotFoundException unreachable) {
            // typeIsOnClasspath already resolved this exact name against this exact loader.
            throw new IllegalStateException(typeName + " resolved and then did not", unreachable);
        }
    }

    /**
     * The number of beans in this context whose declaring class lies in a package or beneath it.
     *
     * @param packageName the package to count under; never {@code null}
     * @return the number of matching beans
     */
    private long beanCountUnder(String packageName) {
        long count = 0;
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(beanName);
            if (type == null) {
                continue;
            }
            String beanPackage = ClassUtils.getUserClass(type).getPackageName();
            if (beanPackage.equals(packageName) || beanPackage.startsWith(packageName + ".")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Reports whether a type is resolvable on this suite's classpath, without initialising it.
     *
     * <p>Initialisation is deliberately suppressed: this is an existence question, and running a static
     * initialiser to answer it would be a side effect.
     *
     * @param typeName the fully-qualified type name; never {@code null}
     * @return {@code true} when the type can be resolved
     */
    private boolean typeIsOnClasspath(String typeName) {
        try {
            Class.forName(typeName, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * The file names of every jar on this run's classpath, enumerated rather than parsed from a property.
     *
     * <p>{@code java.class.path} is not usable for this: Surefire launches the suite through a
     * manifest-only booter jar, so the property names one entry and the real classpath lives in that
     * jar's {@code Class-Path} manifest attribute. Enumerating {@value #MANIFEST_RESOURCE} through the
     * class loader follows that attribute and yields the whole set, which is why the caller's first
     * assertions check the result is a complete view before drawing any conclusion from an absence.
     *
     * <p>Classpath entries that are directories - this module's own {@code target/classes} and
     * {@code target/test-classes} - carry no jar URL and are skipped; only archives are reported.
     *
     * @return the jar file names, one per archive, in enumeration order
     */
    private List<String> runtimeClasspathJarNames() {
        List<String> jarNames = new ArrayList<>();
        try {
            Enumeration<URL> manifests = getClass().getClassLoader().getResources(MANIFEST_RESOURCE);
            while (manifests.hasMoreElements()) {
                String location = manifests.nextElement().toString();
                int entrySeparator = location.indexOf(JAR_ENTRY_SEPARATOR);
                if (entrySeparator < 0) {
                    continue;
                }
                String archive = location.substring(0, entrySeparator);
                jarNames.add(archive.substring(archive.lastIndexOf('/') + 1));
            }
        } catch (IOException enumerationFailed) {
            throw new IllegalStateException(
                    "the classpath could not be enumerated through " + MANIFEST_RESOURCE
                            + ", so the excluded-dependency boundary cannot be proved either way",
                    enumerationFailed);
        }
        return jarNames;
    }

    /**
     * The scan list the annotation actually declares, read from the annotation rather than restated.
     *
     * @return the declared {@code scanBasePackages}, in declaration order
     */
    private static List<String> scanBasePackages() {
        SpringBootApplication annotation =
                CardDemoApplication.class.getDeclaredAnnotation(SpringBootApplication.class);
        assertThat(annotation).as("@SpringBootApplication must be present").isNotNull();
        return List.of(annotation.scanBasePackages());
    }

    /**
     * The job bean name a kebab-case {@code carddemo.jobs} key belongs to.
     *
     * <p>Re-derived here rather than borrowed from the production converter, which is not visible from
     * this package - and independence is a virtue in an assertion: if the two derivations ever
     * disagreed, the comparison in {@code TheBatchSurface} would fail rather than agree with a mistake.
     * The rule is Spring's canonical relaxed-binding form: {@code statement-generation-job-a} is the key
     * of {@code statementGenerationJobA}.
     *
     * @param jobKey the kebab-case key exactly as {@code application.yml} declares it; never
     *     {@code null}
     * @return the camel-case bean name of the job it declares
     */
    private static String jobBeanNameOf(String jobKey) {
        StringBuilder beanName = new StringBuilder(jobKey.length());
        boolean capitaliseNext = false;
        for (int index = 0; index < jobKey.length(); index++) {
            char character = jobKey.charAt(index);
            if (character == '-') {
                capitaliseNext = true;
            } else if (capitaliseNext) {
                beanName.append(Character.toUpperCase(character));
                capitaliseNext = false;
            } else {
                beanName.append(character);
            }
        }
        return beanName.toString();
    }

    /**
     * Keeps only the members a human wrote, discarding those the coverage agent adds.
     *
     * <p>This matters, and getting it wrong produces the worst kind of test: one that is green under
     * {@code mvn test} and red under {@code mvn verify}. The build runs the JaCoCo agent, which
     * instruments every loaded class by adding a {@code private static synthetic $jacocoInit} method and
     * a {@code private static transient synthetic $jacocoData} field, so a bare
     * {@code getDeclaredMethods().length == 1} holds only while coverage is switched off. Both the
     * synthetic flag and the {@code $} in the generated names are checked, because an instrumenting agent
     * that omitted the flag would otherwise slip through.
     *
     * @param members the reflected members; never {@code null}
     * @param <T> the member type
     * @return the authored members, in reflection order
     */
    private static <T extends Member> List<T> authored(T[] members) {
        List<T> authored = new ArrayList<>();
        for (T member : members) {
            if (!member.isSynthetic() && !member.getName().contains("$")) {
                authored.add(member);
            }
        }
        return authored;
    }

    /**
     * This module's own descriptor, {@code app/java/pom.xml}.
     *
     * <p>Resolved from the module's base directory - which Surefire both sets as the fork's working
     * directory and passes as the {@code basedir} system property - and <em>not</em> by walking upwards
     * looking for a marker. The distinction is deliberate: an upward search makes a test's subject
     * depend on where the process happened to start, and this module already had to fix one suite that
     * read its oracle that way. Nothing outside {@code app/java} is read, and nothing at all is written
     * (practice B3, gate G5).
     *
     * @return the path of the module descriptor
     */
    private static Path moduleDescriptor() {
        List<Path> candidates = List.of(
                Path.of(System.getProperty("basedir", ".")).resolve("pom.xml"),
                Path.of(System.getProperty("user.dir", ".")).resolve("pom.xml"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("The module descriptor was not found at " + candidates
                + ". This suite reads app/java/pom.xml from the module's own base directory, which "
                + "Surefire supplies as both the working directory and the 'basedir' system property; "
                + "run it through Maven, or set basedir when running it another way.");
    }
}
