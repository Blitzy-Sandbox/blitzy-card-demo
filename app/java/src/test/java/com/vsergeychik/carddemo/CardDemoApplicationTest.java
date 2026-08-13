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
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.function.RouterFunction;

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
 * Everything the entry point does is declarative, and a declarative mistake is silent.
 */
@SpringBootTest(classes = CardDemoApplication.class)
@ActiveProfiles("test")
@DisplayName("CardDemoApplication - the whole-graph context-load gate (G3)")
class CardDemoApplicationTest {
    private static final String ROOT_PACKAGE = "com.vsergeychik.carddemo";

    private static final String PUBLISHED_FQN = ROOT_PACKAGE + ".CardDemoApplication";

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

    private static final List<String> DATASET_REPOSITORIES = List.of(
            ROOT_PACKAGE + ".account.AccountRepository",
            ROOT_PACKAGE + ".account.DisclosureGroupRepository",
            ROOT_PACKAGE + ".card.CardRepository",
            ROOT_PACKAGE + ".card.CardXrefRepository",
            ROOT_PACKAGE + ".customer.CustomerRepository",
            ROOT_PACKAGE + ".statement.TrnxRepository",
            ROOT_PACKAGE + ".transaction.TransactionRepository",
            ROOT_PACKAGE + ".transaction.DalyTranRepository",
            ROOT_PACKAGE + ".transaction.TranCatBalRepository",
            ROOT_PACKAGE + ".transaction.TranTypeRepository",
            ROOT_PACKAGE + ".transaction.TranCategoryRepository",
            ROOT_PACKAGE + ".user.SecUserRepository");

    private static final List<String> DATASET_WRITERS_AND_READERS = List.of(
            ROOT_PACKAGE + ".transaction.DalyRejectWriter",
            ROOT_PACKAGE + ".transaction.TranReportWriter",
            ROOT_PACKAGE + ".statement.StatementTextWriter",
            ROOT_PACKAGE + ".statement.StatementHtmlWriter",
            ROOT_PACKAGE + ".transaction.DateParmReader");

    private static final List<String> SUBPROGRAM_BEAN_NAMES = List.of(
            "statementGenerationJobB",
            "dateUtilityJob");

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

    private static final List<String> EXCLUDED_BEAN_PACKAGE_PREFIXES = List.of(
            "org.springframework.security",
            "jakarta.persistence",
            "org.hibernate",
            "org.flywaydb",
            "liquibase");

    private static final List<String> ABSENT_REGISTRY_TYPES = List.of(
            "io.micrometer.observation.ObservationRegistry",
            "io.micrometer.core.instrument.MeterRegistry");

    /**
     * The excluded groupIds this module's own descriptor must never declare.
     *
     * <p>This is the form of AAP 0.5.6 that is <strong>this module's to satisfy</strong>, and it is
     * asserted rather than described. {@code io.micrometer} artifacts do reach the effective graph, but
     * only as transitives of {@code spring-boot-starter-web} and {@code spring-boot-starter-batch},
     * which the same plan mandates - and measurement, not assumption, settles that they cannot be
     * excluded: removing {@code micrometer-core} makes every batch job fail at
     * {@code StepBuilderHelper.<init>} with {@code NoClassDefFoundError:
     * io/micrometer/core/instrument/MeterRegistry}. That conflict is between two of the plan's own
     * requirements and only a human can resolve it, so it is escalated rather than absorbed
     * (practice B12); it is <em>not</em> resolved by this suite blessing a list of artifacts as
     * permitted, which would be the test redefining the required graph instead of validating it.
     *
     * <p>What is checkable here is the whole of what the module controls: it declares no coordinate
     * from an excluded family, and no source file of its own names a type from one. Both are asserted
     * by {@link TheExcludedTechnologyBoundary}.
     */
    private static final List<String> UNDECLARABLE_GROUP_IDS = List.of(
            "io.micrometer",
            "io.opentelemetry",
            "io.zipkin.brave",
            "org.springframework.security",
            "org.hibernate.orm",
            "org.flywaydb",
            "org.liquibase",
            "org.postgresql",
            "org.springdoc",
            "org.projectlombok",
            "org.mapstruct",
            "org.testcontainers",
            "software.amazon.awssdk",
            "io.awspring.cloud");

    /**
     * Package prefixes of the excluded families that no source file in this module may name.
     *
     * <p>The complement of {@link #UNDECLARABLE_GROUP_IDS}: that list proves nothing excluded was
     * <em>asked for</em>, this one proves nothing excluded is <em>used</em>. An import, a field type or
     * a fully-qualified reference to any of these would mean the module had started depending on a
     * family the plan excludes, whichever route the jar took onto the classpath.
     */
    private static final List<String> UNUSABLE_TYPE_PREFIXES = List.of(
            "io.micrometer.",
            "io.opentelemetry.",
            "brave.",
            "zipkin2.",
            "org.springframework.security.",
            "org.springframework.boot.actuate.",
            "jakarta.persistence.",
            "org.hibernate.",
            "org.flywaydb.",
            "liquibase.",
            "org.springdoc.",
            "lombok.",
            "org.mapstruct.",
            "org.testcontainers.",
            "org.springframework.web.reactive.");

    private static final List<String> REMOVED_OBSERVABILITY_JAR_PREFIXES = List.of(
            "HdrHistogram-",
            "LatencyUtils-");

    private static final List<String> REMOVED_OBSERVABILITY_TYPES = List.of(
            "org.HdrHistogram.Histogram",
            "org.LatencyUtils.PauseDetector");

    /**
     * Jar-name prefixes of the expression-compiler family that logback's conditional configuration
     * needs, and which must therefore stay off the classpath.
     *
     * <p>CVE-2026-13006 is arbitrary code execution through the Janino-evaluated {@code condition}
     * attribute of an {@code <if>} element in a logback configuration file, and the CVE record states
     * the precondition plainly: the attack requires Janino to be present on the class path. Nothing in
     * AAP 0.5.6 asks for an expression compiler and no declared dependency brings one, so its absence
     * is a property worth holding rather than a coincidence worth assuming - a transitive graph shifts
     * under a version bump, and this is the check that notices.
     */
    private static final List<String> ABSENT_EXPRESSION_COMPILER_JAR_PREFIXES = List.of(
            "janino-",
            "commons-compiler-");

    /**
     * The marker types of that family, checked by resolution as well as by jar name for the same
     * reason the removed observability artifacts are checked both ways.
     */
    private static final List<String> ABSENT_EXPRESSION_COMPILER_TYPES = List.of(
            "org.codehaus.janino.ScriptEvaluator",
            "org.codehaus.commons.compiler.IScriptEvaluator");

    /**
     * The logback jars whose version must sit at or above the release that fully fixes
     * CVE-2026-13006.
     */
    private static final List<String> LOGBACK_JAR_PREFIXES = List.of(
            "logback-classic-",
            "logback-core-");

    /**
     * The lowest logback release that fully fixes CVE-2026-13006, as the logback project states it.
     *
     * <p>1.5.35 and 1.5.36 each hardened the condition denylist and each carries the vendor's own
     * caveat that <em>1.5.37</em> provides the full fix, so the boundary is 1.5.37 and not the first
     * release that mentions the CVE. {@code app/java/pom.xml} pins {@code logback.version} to 1.5.38,
     * above the boundary; this constant is what makes a silent regression below it fail the build,
     * whether it arrives by the pin being deleted, by a parent BOM bump, or by a hand edit.
     */
    private static final List<Integer> LOGBACK_MINIMUM_FIXED_VERSION = List.of(1, 5, 37);

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

    private static final String MANIFEST_RESOURCE = "META-INF/MANIFEST.MF";

    private static final String JAR_ENTRY_SEPARATOR = "!/";

    private static final List<String> CLASSPATH_PROBE_ANCHORS = List.of(
            "spring-core-",
            "micrometer-core-");

    private static final int CLASSPATH_PROBE_FLOOR = 20;

    /**
     * The smallest file count a complete walk of this module's two Java source roots can produce.
     *
     * <p>The same self-check the classpath floor performs, for the same reason: a scan that walked the
     * wrong directory and found nothing would report "no excluded family is used" over an empty search.
     * Deliberately well below the real total, so it fails on a broken walk rather than on ordinary
     * growth of the module.
     */
    private static final int SOURCE_SCAN_FLOOR = 200;

    /**
     * The test-only package that holds the stereotyped fixtures, and the one package under the root
     * that must stay <em>out</em> of the scan list.
     */
    private static final String TEST_SUPPORT_PACKAGE = ROOT_PACKAGE + ".testsupport";

    private static final String CLASS_SUFFIX = ".class";

    private static final String CONTROLLER_DESCRIPTOR =
            "Lorg/springframework/stereotype/Controller;";

    private static final String REST_CONTROLLER_DESCRIPTOR =
            "Lorg/springframework/web/bind/annotation/RestController;";

    private static final Pattern MAIN_CLASS =
            Pattern.compile("<mainClass>\\s*([^<\\s]+)\\s*</mainClass>");

    private static final Pattern LAYOUT =
            Pattern.compile("<layout>\\s*([^<\\s]+)\\s*</layout>");

    private final ApplicationContext context;

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
            assertThat(context.getBeanDefinitionCount())
                    .as("the graph is populated, not an empty container")
                    .isGreaterThan(SCREEN_CONTROLLERS.size() + JOB_BEAN_NAMES.size()
                            + DATASET_REPOSITORIES.size());

            assertThat(context).isInstanceOf(ConfigurableApplicationContext.class);
            assertThat(((ConfigurableApplicationContext) context).isActive())
                    .as("active means refreshed and not yet closed")
                    .isTrue();
        }

        @Test
        @DisplayName("the 'test' profile is the only one active, so the H2 DataSource is what is wired")
        void theTestProfileIsActive() {
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

            assertThat(ClassUtils.getUserClass(context.getBean(CardDemoApplication.class).getClass())
                    .getName())
                    .as("the published fully-qualified name, which app/java/pom.xml carries as a "
                            + "string and the repackaged jar carries as its Start-Class")
                    .isEqualTo(PUBLISHED_FQN);
        }
    }

    @Nested
    @DisplayName("The online surface - 17 screen controllers, and the route that replaces Boot's error page")
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
        @DisplayName("the controller inventory is exactly those 17 and nothing else")
        void theControllerInventoryIsClosed() {
            // Two-sided, and the second side is the point. A "contains" assertion would pass over an
            // eighteenth controller - an invented program, or a test fixture that drifted into a
            // scanned package - and one such fixture really did join this context once, publishing ten
            // routes the deployed artifact does not have.
            //
            // The inventory is CLOSED AT SEVENTEEN, with no exception carved out for infrastructure.
            // The container's error path is answered here too, but by a RouterFunction bean
            // (WebConfig.CobolErrorRoute) rather than by a controller, precisely so that this count can
            // stay exactly the number of translated CICS online programs. A count that had to permit
            // "seventeen screens plus one that is not a screen" is a count that absorbs the next
            // accidental addition without noticing.
            assertThat(beansByUserClassName(context.getBeanNamesForAnnotation(RestController.class))
                    .keySet())
                    .as("the whole online surface, and the whole of it: one @RestController per CICS "
                            + "online program, seventeen in total")
                    .containsExactlyInAnyOrderElementsOf(SCREEN_CONTROLLERS);
        }

        @Test
        @DisplayName("the error path is answered by a RouterFunction, not by an eighteenth controller")
        void theErrorPathIsInfrastructureRatherThanAScreen() {
            // The two properties that let the seventeen-controller inventory above stay closed while the
            // JSON error boundary keeps working, asserted together because either alone would be
            // misleading.
            //
            // First: the route exists and carries no controller stereotype. RouterFunctionMapping
            // composes every RouterFunction bean in the context into one HandlerMapping, so the
            // container's forward to the error path finds this handler exactly as it previously found an
            // annotated mapping.
            assertThat(context.getBeanNamesForType(WebConfig.CobolErrorRoute.class))
                    .as("the error route must be published as a bean, or the container's forward falls "
                            + "through to Boot's whitelabel page")
                    .hasSize(1);
            assertThat(WebConfig.CobolErrorRoute.class.isAnnotationPresent(RestController.class))
                    .as("the error route must NOT be a @RestController: the controller inventory is the "
                            + "seventeen translated programs and nothing else (gate G3)")
                    .isFalse();
            assertThat(WebConfig.CobolErrorRoute.class.isAnnotationPresent(Controller.class))
                    .as("nor a @Controller, for the same reason")
                    .isFalse();
            assertThat(RouterFunction.class)
                    .as("it is infrastructure routing, which is how it maps a path without a stereotype")
                    .isAssignableFrom(WebConfig.CobolErrorRoute.class);

            // Second: it is still an ErrorController, which is the whole mechanism that suppresses
            // Boot's own. ErrorMvcAutoConfiguration declares BasicErrorController
            // @ConditionalOnMissingBean(ErrorController.class), so this bean's TYPE is what switches the
            // text/html whitelabel mapping off. Asserting the absence of Boot's controller is what
            // proves the mechanism actually fired.
            assertThat(ErrorController.class)
                    .as("implementing the marker interface is what makes Boot's own controller back off")
                    .isAssignableFrom(WebConfig.CobolErrorRoute.class);
            assertThat(context.getBeanNamesForType(BasicErrorController.class))
                    .as("Boot's HTML-producing BasicErrorController must not be registered; if it is, "
                            + "the whitelabel page is reachable from an API that speaks only JSON")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(ErrorController.class))
                    .as("exactly one ErrorController, and it is this module's route")
                    .containsExactly(context.getBeanNamesForType(WebConfig.CobolErrorRoute.class)[0]);
        }

        @Test
        @DisplayName("the online surface is 17 screens - the CSD's 18 programs less the one with no source")
        void theOnlineSurfaceIsSeventeenScreens() {
            assertThat(SCREEN_CONTROLLERS)
                    .as("one entry per CICS online program that actually has source")
                    .hasSize(17)
                    .doesNotHaveDuplicates();

            List<String> screens = new ArrayList<>(beansByUserClassName(
                    context.getBeanNamesForAnnotation(RestController.class)).keySet());

            assertThat(screens)
                    .as("seventeen screen controllers and not an eighteenth")
                    .hasSize(17)
                    .containsExactlyInAnyOrderElementsOf(SCREEN_CONTROLLERS);
        }

        @Test
        @DisplayName("@RestController and @Controller select the same beans, so none is MVC-only")
        void everyControllerIsAJsonController() {
            assertThat(beansByUserClassName(context.getBeanNamesForAnnotation(Controller.class))
                    .keySet())
                    .containsExactlyInAnyOrderElementsOf(beansByUserClassName(
                            context.getBeanNamesForAnnotation(RestController.class)).keySet());
        }

        @Test
        @DisplayName("the report screen's job-submission port is wired, replacing the CICS TDQ write")
        void theJobSubmissionPortIsWired() {
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
            assertThat(context.getBeanNamesForType(Job.class)).contains("transactionPostingJob");
            assertThat(context.getBean("transactionPostingJob", Job.class).getName())
                    .isEqualTo("transactionPostingJob");
        }

        @Test
        @DisplayName("neither called subprogram is a Job, despite a mandated name that ends in 'Job'")
        void neitherSubprogramIsAJob() {
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

        @Test
        @DisplayName("the running graph carries the programs the JCL names, and no others")
        void theRunningGraphCarriesTheSourceDerivedPrograms() {
            // The last link in the chain that answers the plan's summary total of ten. Which programs
            // are jobs is a fact about the JCL, and it is derived from the decks themselves - not from
            // this list - by ConfigBranchCoverageTest.JobContractValidation, which reads app/jcl and
            // app/proc off the test classpath and counts EXEC PGM= for itself. What is asserted here is
            // that the STARTED context carries exactly those programs: eight that a JCL step invokes
            // and the one orphan that none does. Nothing else may appear, and in particular neither
            // CBSTM03B nor CSUTLDTC, whose mandated names end in "Job" (gate G12).
            BatchConfig batchConfig = context.getBean(BatchConfig.class);
            List<String> programs = batchConfig.jobContracts().keySet().stream()
                    .map(key -> batchConfig.contract(key).program())
                    .sorted()
                    .toList();

            assertThat(programs)
                    .as("the nine PROGRAM-IDs the batch surface migrates, and a tenth would have to be "
                            + "a program the JCL invokes - there is none")
                    .containsExactly("CBACT01C", "CBACT02C", "CBACT03C", "CBACT04C", "CBCUS01C",
                            "CBSTM03A", "CBTRN01C", "CBTRN02C", "CBTRN03C");
            assertThat(programs)
                    .as("the two called subprograms are a @Component and a @Service, so neither may "
                            + "hold a job contract however its mandated class name reads")
                    .doesNotContain("CBSTM03B", "CSUTLDTC");
        }
    }

    @Nested
    @DisplayName("The data-access surface - 12 repositories, 4 writers, 1 parameter reader")
    class TheDataAccessSurface {
        @Test
        @DisplayName("exactly the twelve dataset repositories are registered, one per base dataset")
        void exactlyTheTwelveRepositoriesAreRegistered() {
            Map<String, List<String>> byType = beansByUserClassName(
                    context.getBeanNamesForAnnotation(Repository.class));

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
        @DisplayName("the two single-caller datasets have repositories of their own, and their callers "
                + "take them as collaborators")
        void theSingleCallerDatasetsHaveRepositoriesToo()
                throws ClassNotFoundException, NoSuchMethodException {
            // DISCGRP is read by one program and TRNXFILE by one component, and both are repositories
            // all the same (gate G10). The count above already requires the beans; what this adds is
            // that they are actually WIRED INTO their consumers rather than published beside them,
            // which is the way a repository can exist and still leave the access path where it was.
            Class<?> discgrp = Class.forName(ROOT_PACKAGE + ".account.DisclosureGroupRepository");
            Class<?> trnx = Class.forName(ROOT_PACKAGE + ".statement.TrnxRepository");

            // The interest job takes the DISCGRP port; DisclosureGroupRepository is the one bean that
            // satisfies it, and the port is what lets a unit test drive all four read outcomes with no
            // database at all.
            Class<?> port = Class.forName(
                    ROOT_PACKAGE + ".account.AccountInterestCalcJob$DisclosureGroupAccess");
            assertThat(port).as("DisclosureGroupRepository must satisfy the interest job's DISCGRP port")
                    .isAssignableFrom(discgrp);
            assertThat(context.getBeanNamesForType(port))
                    .as("exactly one bean satisfies the DISCGRP port, and it is the repository")
                    .containsExactly(context.getBeanNamesForType(discgrp)[0]);

            // The statement component declares the TRNXFILE repository as a constructor parameter, so
            // the dataset's identity and geometry can only come from it.
            Class<?> component = Class.forName(ROOT_PACKAGE + ".statement.StatementGenerationJobB");
            assertThat(component.getDeclaredConstructors())
                    .as("StatementGenerationJobB must take TrnxRepository as a collaborator")
                    .anySatisfy(constructor -> assertThat(constructor.getParameterTypes())
                            .contains(trnx));
        }

        @Test
        @DisplayName("exactly one DataSource is wired, and no dataset name is hard-coded into Java")
        void exactlyOneDataSourceIsWired() {
            assertThat(context.getBeanNamesForType(DataSource.class))
                    .as("one pooled DataSource; under this profile it is the in-memory database, and "
                            + "in production it is the deployment-supplied driver")
                    .containsExactly("dataSource");

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
            assertThat(context.getBeanNamesForType(DataSourceConfig.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(BatchConfig.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(WebConfig.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(CobolCharsetConfig.class)).hasSize(1);
        }

        @Test
        @DisplayName("exactly one Clock bean exists, so every date-bearing screen has its instant source")
        void exactlyOneClockIsPublished() {
            assertThat(context.getBeanNamesForType(Clock.class)).containsExactly("clock");

            Clock clock = context.getBean(Clock.class);
            assertThat(clock).isNotNull();

            assertThat(clock.getZone())
                    .as("the production clock follows the platform zone and is never a fixed instant")
                    .isEqualTo(ZoneId.systemDefault());
        }

        @Test
        @DisplayName("the three charsets are named beans, so no dataset is decoded by platform default")
        void theCharsetsAreNamedBeans() {
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
            assertThat(context.getBeanNamesForType(JobRepository.class)).containsExactly("jobRepository");
            assertThat(context.getBean(JobRepository.class)).isNotNull();
        }

        @Test
        @DisplayName("the JobRepository really queries its database, so it is backed rather than stubbed")
        void theJobRepositoryQueriesItsDatabase() {
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
            assertThat(context.getBeanNamesForType(PlatformTransactionManager.class))
                    .containsExactly("transactionManager");
            assertThat(context.getBean(PlatformTransactionManager.class)).isNotNull();
        }

        @Test
        @DisplayName("the batch step beans are registered for the jobs that declare them")
        void theDeclaredStepBeansAreRegistered() {
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
            EXCLUDED_TECHNOLOGY_TYPES.forEach((family, type) ->
                    assertThat(typeIsOnClasspath(type))
                            .as("%s is excluded by the plan's closed dependency set, so %s must not be "
                                    + "resolvable", family, type)
                            .isFalse());
        }

        @Test
        @DisplayName("no bean comes from a security, persistence or schema-migration package")
        void noBeanComesFromAnExcludedPackage() {
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
            ABSENT_REGISTRY_TYPES.forEach(registryType ->
                    assertThat(beanNamesOfType(registryType))
                            .as("%s must have no bean: nothing is collected, aggregated or exported",
                                    registryType)
                            .isEmpty());
        }

        @Test
        @DisplayName("logback is at or above its fixed release, and the expression compiler its CVE needs is absent")
        void theLoggingBackendIsPatchedAndItsExpressionCompilerIsAbsent() {
            // Two independent facts make CVE-2026-13006 unreachable here, and this test holds both
            // rather than relying on either alone. The advisory covers logback-core "up to and
            // including 1.5.34" - which is exactly what spring-boot-dependencies-3.5.16 manages - so
            // app/java/pom.xml pins logback.version to 1.5.38. And the exploit needs Janino on the
            // classpath, which nothing here declares.
            //
            // Either fact alone would close it, so asserting both is deliberate: a version pin can be
            // deleted by a well-meaning "stop overriding the BOM" cleanup, and a transitive graph can
            // acquire an expression compiler under a version bump without anybody declaring one. The
            // pair fails on whichever happens first.
            //
            // Prove the instrument before trusting an absence, exactly as the sibling test does: a
            // classpath enumeration that saw only the surefire booter jar would make the Janino
            // assertion pass over an empty search.
            List<String> classpathJars = runtimeClasspathJarNames();
            assertThat(classpathJars)
                    .as("the classpath enumeration must see the whole test classpath, or the absence "
                            + "of an expression compiler below proves nothing")
                    .hasSizeGreaterThanOrEqualTo(CLASSPATH_PROBE_FLOOR);

            // The version floor. Read off the jar names rather than from a constant in the pom, so
            // this checks what actually resolved instead of what was requested.
            LOGBACK_JAR_PREFIXES.forEach(prefix -> {
                List<String> matching = classpathJars.stream()
                        .filter(jar -> jar.startsWith(prefix))
                        .sorted()
                        .toList();
                assertThat(matching)
                        .as("%s* must be on the classpath - logback is the Boot-managed logging "
                                + "backend, so its absence would mean this check is looking in the "
                                + "wrong place", prefix)
                        .hasSize(1);
                List<Integer> resolved = versionOf(matching.get(0), prefix);
                assertThat(compareVersions(resolved, LOGBACK_MINIMUM_FIXED_VERSION))
                        .as("%s resolved to %s, which is below %s - the release the logback project "
                                + "states fully fixes CVE-2026-13006. Restore the logback.version pin "
                                + "in app/java/pom.xml; note that 1.5.35 and 1.5.36 are NOT the fix "
                                + "boundary, because each carries the vendor's own caveat that 1.5.37 "
                                + "provides the full fix.",
                                matching.get(0), resolved, LOGBACK_MINIMUM_FIXED_VERSION)
                        .isNotNegative();
            });

            // The precondition. Checked by jar name and again by type resolution, because the two
            // techniques fail in opposite directions.
            ABSENT_EXPRESSION_COMPILER_JAR_PREFIXES.forEach(absent ->
                    assertThat(classpathJars)
                            .as("%s* is the expression compiler CVE-2026-13006 requires on the "
                                    + "classpath, and nothing in AAP 0.5.6 asks for one, so it must "
                                    + "not be here. If it is genuinely needed, say so deliberately - "
                                    + "and then the logback version floor above is load-bearing rather "
                                    + "than defence in depth.", absent)
                            .noneMatch(jar -> jar.startsWith(absent)));
            ABSENT_EXPRESSION_COMPILER_TYPES.forEach(absentType ->
                    assertThat(typeIsOnClasspath(absentType))
                            .as("%s belongs to the expression-compiler family CVE-2026-13006 needs, so "
                                    + "it must not resolve", absentType)
                            .isFalse());
        }

        @Test
        @DisplayName("the removable part of the excluded observability surface is gone, and nothing that "
                + "could collect, export or trace is present at all")
        void theObservabilitySurfaceIsCutToWhatCannotBeRemoved() {
            // The bean-graph assertion above proves nothing is collected. This proves the graph itself is
            // cut back: the two artifacts app/java/pom.xml can exclude are absent, and every registry
            // implementation, exporter, tracing bridge and management surface is absent outright. That
            // second half is the part of AAP 0.5.6 that IS fully achievable, so it is an absolute here.
            //
            // What this test deliberately does NOT do is enumerate the excluded-family artifacts it will
            // tolerate. A permitted-list would be this suite deciding what the required graph is instead
            // of checking it against the plan; the three irreducible micrometer API jars are a conflict
            // between two of the plan's own requirements, escalated as such, and the module's own
            // obligations are asserted separately by noExcludedFamilyIsDeclared and noExcludedFamilyIsUsed.
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

            ABSENT_OBSERVABILITY_TYPES.forEach((family, type) ->
                    assertThat(typeIsOnClasspath(type))
                            .as("%s is excluded by AAP 0.5.6, so %s must not be resolvable",
                                    family, type)
                            .isFalse());

        }

        @Test
        @DisplayName("the module declares no coordinate from an excluded family, so nothing excluded "
                + "was asked for")
        void noExcludedFamilyIsDeclared() throws IOException {
            // The half of AAP 0.5.6 that is entirely this module's to satisfy, and the only half a
            // descriptor can answer: what was DECLARED. Read from the descriptor rather than from the
            // resolved graph on purpose - a transitive of a mandated starter is the plan's own doing,
            // whereas a declared coordinate is this module's, and only the second is a violation this
            // file can commit. Asserted here because Maven holds every coordinate as plain XML, outside
            // the compiler's symbol graph, exactly as it holds mainClass.
            String pom = Files.readString(moduleDescriptor(), StandardCharsets.UTF_8);
            UNDECLARABLE_GROUP_IDS.forEach(groupId ->
                    assertThat(pom)
                            .as("AAP 0.5.6 excludes %s, so app/java/pom.xml must declare no dependency "
                                    + "on it - and a <groupId> element naming it is the only way one "
                                    + "could be declared", groupId)
                            .doesNotContain("<groupId>" + groupId + "</groupId>"));
        }

        @Test
        @DisplayName("no source file in this module names a type from an excluded family, so nothing "
                + "excluded is used")
        void noExcludedFamilyIsUsed() throws IOException {
            // The companion to the descriptor check, and the one that closes the gap the descriptor
            // cannot: three io.micrometer artifacts ARE on the effective classpath, as irreducible
            // transitives of the two starters AAP 0.5.6's own plan mandates. What matters then is
            // whether this module's code reaches for them, and that is answered by reading the source
            // rather than the graph. A single import would mean the exclusion had been abandoned rather
            // than obstructed.
            //
            // The scan covers main AND test sources: a test that touched a MeterRegistry would be
            // exercising the excluded family just as surely as production code would.
            List<Path> sourceRoots = List.of(
                    moduleDescriptor().resolveSibling(Path.of("src", "main", "java")),
                    moduleDescriptor().resolveSibling(Path.of("src", "test", "java")));
            Map<Path, String> offenders = new LinkedHashMap<>();
            int scanned = 0;
            for (Path root : sourceRoots) {
                assertThat(root).as("source root %s must exist for this scan to mean anything", root)
                        .isDirectory();
                try (Stream<Path> files = Files.walk(root)) {
                    List<Path> javaFiles = files.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(".java"))
                            .sorted()
                            .toList();
                    scanned += javaFiles.size();
                    for (Path file : javaFiles) {
                        String body = Files.readString(file, StandardCharsets.UTF_8);
                        UNUSABLE_TYPE_PREFIXES.stream()
                                // "import x.y." and a bare "x.y.Type" reference are both reaches into
                                // the family; the prose in this very suite names io.micrometer types
                                // while explaining the conflict, so only real code is considered - an
                                // import statement, or the prefix used as a type outside a comment.
                                .filter(prefix -> body.contains("import " + prefix)
                                        || body.contains("import static " + prefix))
                                .forEach(prefix -> offenders.put(file, prefix));
                    }
                }
            }
            assertThat(scanned)
                    .as("the scan must see this module's whole source tree, or its result proves nothing")
                    .isGreaterThanOrEqualTo(SOURCE_SCAN_FLOOR);
            assertThat(offenders)
                    .as("no source file may import a type from a family AAP 0.5.6 excludes; found %s",
                            offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("no schema is created for the application's own data")
        void noApplicationSchemaIsCreated() {
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

        private boolean declaresAControllerStereotype(Path classFile) throws IOException {
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return bytes.contains(CONTROLLER_DESCRIPTOR) || bytes.contains(REST_CONTROLLER_DESCRIPTOR);
        }

        private boolean liesInAScannedPackage(String className) {
            return scanBasePackages().stream()
                    .anyMatch(scanned -> className.startsWith(scanned + "."));
        }

        private String classNameOf(Path root, Path classFile) {
            String relative = root.relativize(classFile).toString();
            return relative.substring(0, relative.length() - CLASS_SUFFIX.length())
                    .replace(File.separatorChar, '.');
        }

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

            assertThat(ApplicationRunner.class.isAssignableFrom(CardDemoApplication.class)).isFalse();
            assertThat(CommandLineRunner.class.isAssignableFrom(CardDemoApplication.class)).isFalse();
            assertThat(CardDemoApplication.class.getInterfaces()).isEmpty();
            assertThat(CardDemoApplication.class.getSuperclass()).isEqualTo(Object.class);
        }
    }

    @Nested
    @DisplayName("The launch mode - a JCL submission is a one-shot process, not a web application")
    class LaunchMode {
        private static final UnaryOperator<String> NOTHING_EXTERNAL = name -> null;

        private static final String A_PUBLISHED_JOB = "accountBalanceJob";

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

    @Configuration
    static class LaunchProbe {
    }

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

    private List<String> beanNamesOfType(String typeName) {
        if (!typeIsOnClasspath(typeName)) {
            return List.of();
        }
        try {
            return List.of(context.getBeanNamesForType(
                    Class.forName(typeName, false, getClass().getClassLoader())));
        } catch (ClassNotFoundException unreachable) {
            throw new IllegalStateException(typeName + " resolved and then did not", unreachable);
        }
    }

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
     * The numeric version components of a jar, read from its filename.
     *
     * <p>Reading the resolved artifact rather than a declared property is the point: a pin that was
     * written but overridden, or deleted, shows up here and nowhere else. Trailing non-numeric
     * components - a qualifier such as {@code -SNAPSHOT} - stop the parse rather than failing it, so
     * the comparison is over the numeric prefix the two versions share.
     *
     * @param jarName the jar's simple filename, for example {@code logback-core-1.5.38.jar}
     * @param prefix  the artifact-name prefix to strip, for example {@code logback-core-}
     * @return the numeric components in order, never empty
     * @throws AssertionError if no leading numeric component can be read, since a version this check
     *                        cannot parse must fail loudly rather than compare as equal
     */
    private static List<Integer> versionOf(String jarName, String prefix) {
        String remainder = jarName.substring(prefix.length());
        int extension = remainder.lastIndexOf(".jar");
        if (extension >= 0) {
            remainder = remainder.substring(0, extension);
        }
        List<Integer> components = new ArrayList<>();
        for (String component : remainder.split("\\.")) {
            if (!component.matches("\\d+")) {
                break;
            }
            components.add(Integer.valueOf(component));
        }
        assertThat(components)
                .as("the version of %s could not be read from its filename, so the floor it is being "
                        + "compared against cannot be enforced", jarName)
                .isNotEmpty();
        return List.copyOf(components);
    }

    /**
     * Compares two version component lists element by element, shorter-is-lower on a common prefix.
     *
     * @param actual   the version that resolved
     * @param required the floor it must meet
     * @return negative when {@code actual} is lower, zero when equal, positive when higher
     */
    private static int compareVersions(List<Integer> actual, List<Integer> required) {
        for (int index = 0; index < Math.max(actual.size(), required.size()); index++) {
            int left = index < actual.size() ? actual.get(index) : 0;
            int right = index < required.size() ? required.get(index) : 0;
            if (left != right) {
                return left < right ? -1 : 1;
            }
        }
        return 0;
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

    private static List<String> scanBasePackages() {
        SpringBootApplication annotation =
                CardDemoApplication.class.getDeclaredAnnotation(SpringBootApplication.class);
        assertThat(annotation).as("@SpringBootApplication must be present").isNotNull();
        return List.of(annotation.scanBasePackages());
    }

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

    private static <T extends Member> List<T> authored(T[] members) {
        List<T> authored = new ArrayList<>();
        for (T member : members) {
            if (!member.isSynthetic() && !member.getName().contains("$")) {
                authored.add(member);
            }
        }
        return authored;
    }

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
