package com.vsergeychik.carddemo;

import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

import com.vsergeychik.carddemo.config.BatchConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.util.StringUtils;

/**
 * Spring Boot entry point for the CardDemo COBOL-to-Java migration, and the composition root of
 * this module.
 *
 * <p>The fully-qualified name {@code com.vsergeychik.carddemo.CardDemoApplication} is a
 * <strong>published contract</strong>, not an internal convention: {@code app/java/pom.xml}
 * configures it as the {@code spring-boot-maven-plugin} {@code mainClass}, so it is the
 * {@code Start-Class} of the repackaged jar and the target of {@code spring-boot:run}, and
 * {@code docs/project-guide.md} names the file in the module's entry-point inventory. Maven holds
 * that {@code mainClass} value as a plain <em>string</em>, entirely outside the compiler's symbol
 * graph, so renaming this class or moving it out of {@code com.vsergeychik.carddemo} leaves the
 * build green and the jar unbootable. It is also the root of every
 * {@code COPY}-to-{@code import} correspondence in the migration - every generated type's name
 * begins with this package - which is why the {@code com.vsergeychik} segment above it deliberately
 * holds no code at all.
 *
 * <h2>Why the scan list is written out</h2>
 * <p>{@code @SpringBootApplication} on this package would scan it and everything beneath it, which
 * happens to cover all eleven packages named below, so the list is redundant to Spring. It is
 * nonetheless stated explicitly because being explicit at a boundary pays for itself three times
 * here: it records the module's package assignment in code where a reader of the entry point will
 * see it; it makes that assignment mechanically checkable rather than a matter of directory layout;
 * and it means a package added outside the sanctioned set is <em>not</em> silently picked up as
 * component scope. The order below is the architecture rather than the alphabet - the two
 * foundation packages first, then the nine domain packages that hold the twenty-eight translated
 * programs: {@code account} 6, {@code card} 3, {@code customer} 1, {@code user} 5,
 * {@code transaction} 7, {@code admin} 2, {@code billing} 1, {@code statement} 2 and {@code util} 1.
 *
 * <h2>Batch jobs are launched explicitly, never at startup</h2>
 * <p>{@code src/main/resources/application.yml} sets {@code spring.batch.job.enabled: false}, so
 * starting this application does <strong>not</strong> run any Spring Batch job. That property is
 * load-bearing rather than incidental: the module carries ten batch jobs, and one of them - the job
 * translated from the JCL-orphaned {@code CBTRN01C} - must remain fully runnable while having no
 * trigger of any kind, exactly as the legacy program has no {@code EXEC PGM=} anywhere in
 * {@code app/jcl} or {@code app/proc}. Nothing here contradicts that property: this class registers
 * no {@code ApplicationRunner} and no {@code CommandLineRunner}, launches nothing, and does not
 * restate the property in code.
 *
 * <p>For the same reason this class carries no {@code @EnableBatchProcessing}. Under Spring Boot 3
 * that annotation <em>switches off</em> the Batch auto-configuration rather than switching it on,
 * which would leave the {@code JobRepository} and transaction manager that {@code config} wires
 * unsatisfied. Batch support arrives through auto-configuration and is configured in
 * {@code config/BatchConfig}, which is where it belongs.
 *
 * <h2>Two launch modes, and which one is chosen is decided before the context exists</h2>
 * <p>The same jar is both the online application and the batch submission utility, and those two are
 * different kinds of process. Serving the seventeen translated CICS transactions means a servlet
 * container that stays up until it is stopped. Submitting one JCL job means the opposite: a one-shot
 * process that does exactly one thing and ends, returning the job's {@code RETURN-CODE} so the next
 * job's {@code COND} test can read it (gate G35).
 *
 * <p>So when {@code carddemo.batch.job-name} is supplied - the property that also brings
 * {@code BatchConfig}'s launcher into existence - the application is started with
 * {@link WebApplicationType#NONE}. No container is started, nothing binds a port, and the process
 * holds no non-daemon thread that would keep it alive after the submission finishes. Without the
 * property nothing changes: the web application starts exactly as before.
 *
 * <p>The decision has to be taken <em>here</em>, because the web application type is chosen while the
 * environment is being prepared and cannot be changed afterwards. It reads the property from the three
 * places an operator can supply it - the command line, a system property, and the relaxed environment
 * variable form {@code CARDDEMO_BATCH_JOB_NAME} - and an explicit
 * {@code spring.main.web-application-type} still overrides it, because Spring Boot binds
 * {@code spring.main.*} onto the application after this setter has run.
 *
 * <p>Everything else this class might plausibly have grown is deliberately absent - no additional
 * {@code @ComponentScan} beside {@code scanBasePackages}, no scheduling or async enablement, no
 * persistence-mapping annotations, no security, cloud, or observability wiring, no banner or
 * default-property customisation, and no profile activated from code. Data access is plain JDBC over
 * the existing datasets with no schema change, and every dataset name is resolved from
 * {@code application.yml} rather than written into Java, so this file names no dataset at all.
 *
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication(scanBasePackages = {
    "com.vsergeychik.carddemo.common",
    "com.vsergeychik.carddemo.config",
    "com.vsergeychik.carddemo.account",
    "com.vsergeychik.carddemo.card",
    "com.vsergeychik.carddemo.customer",
    "com.vsergeychik.carddemo.user",
    "com.vsergeychik.carddemo.transaction",
    "com.vsergeychik.carddemo.admin",
    "com.vsergeychik.carddemo.billing",
    "com.vsergeychik.carddemo.statement",
    "com.vsergeychik.carddemo.util"
})
public class CardDemoApplication {

    /**
     * The relaxed environment-variable spelling of {@value BatchConfig.JclJobLauncher#JOB_NAME_PROPERTY},
     * which is how the property is most often supplied to a container: {@value}.
     *
     * <p>Derived rather than transcribed - {@link #environmentVariableFor(String)} produces it from the
     * property name - so the two cannot drift apart.
     */
    static final String JOB_NAME_ENVIRONMENT_VARIABLE =
            environmentVariableFor(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY);

    /**
     * Boots the application context, in the mode the invocation asks for.
     *
     * @param args the command-line arguments, passed through to Spring Boot unchanged so that the
     *     standard property, profile and job-parameter arguments behave as documented
     */
    public static void main(String[] args) {
        springApplicationFor(args, CardDemoApplication::processValueOf).run(args);
    }

    /**
     * The application to run: this class as the source, in the launch mode the invocation asks for.
     *
     * <p>Separate from {@link #main(String[])} and taking its outside world as an argument, so that the
     * mode decision is assertable without starting anything.
     *
     * @param args           the command-line arguments
     * @param processValues  resolves a property name against the process's own environment - system
     *                       properties and environment variables
     * @return the configured application, not yet run
     */
    static SpringApplication springApplicationFor(String[] args, UnaryOperator<String> processValues) {
        SpringApplication application = new SpringApplication(CardDemoApplication.class);
        application.setWebApplicationType(webApplicationTypeFor(args, processValues));
        return application;
    }

    /**
     * {@link WebApplicationType#NONE} for a JCL submission, {@link WebApplicationType#SERVLET}
     * otherwise.
     *
     * <p>{@code SERVLET} is stated rather than deduced. Deduction would reach the same answer, because
     * the servlet starter is a fixed dependency of this module, but stating it makes the pair of modes
     * visible in one expression instead of one mode being explicit and the other implicit.
     *
     * @param args          the command-line arguments
     * @param processValues resolves a property name against the process's own environment
     * @return the launch mode
     */
    static WebApplicationType webApplicationTypeFor(String[] args, UnaryOperator<String> processValues) {
        return isJclSubmission(args, processValues)
                ? WebApplicationType.NONE
                : WebApplicationType.SERVLET;
    }

    /**
     * Whether this invocation is a JCL submission rather than a request to serve the online screens.
     *
     * <p>It is a submission when {@value BatchConfig.JclJobLauncher#JOB_NAME_PROPERTY} is supplied, and
     * that is exactly the condition that brings {@code BatchConfig}'s launcher into existence, so the
     * two halves cannot disagree about what kind of process this is.
     *
     * @param args          the command-line arguments; must not be {@code null}
     * @param processValues resolves a property name against the process's own environment; must not be
     *                      {@code null}
     * @return {@code true} when a job name was supplied
     * @throws NullPointerException if either argument is {@code null}
     */
    static boolean isJclSubmission(String[] args, UnaryOperator<String> processValues) {
        Objects.requireNonNull(args, "The command-line arguments are required to decide the launch mode");
        Objects.requireNonNull(processValues, "A process-value lookup is required to decide the launch "
                + "mode: the job name may arrive as a system property or an environment variable rather "
                + "than on the command line");
        if (new SimpleCommandLinePropertySource(args)
                .containsProperty(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY)) {
            return true;
        }
        return StringUtils.hasText(
                processValues.apply(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY));
    }

    /**
     * A property's value as this process was given it: the system property if there is one, otherwise
     * the relaxed environment variable.
     *
     * <p>The system property wins, which is the same precedence Spring's own environment applies, so a
     * {@code -D} override behaves here as it does everywhere else.
     *
     * @param propertyName the property name, in dotted form
     * @return the value, or {@code null} when the process supplies neither form
     */
    static String processValueOf(String propertyName) {
        String fromSystemProperties = System.getProperty(propertyName);
        return StringUtils.hasText(fromSystemProperties)
                ? fromSystemProperties
                : System.getenv(environmentVariableFor(propertyName));
    }

    /**
     * The relaxed environment-variable spelling of a dotted property name: upper case, with every
     * separator replaced by an underscore.
     *
     * <p>This is Spring Boot's own relaxed-binding rule for environment variables, reproduced here
     * because the decision is taken before any {@code Environment} exists to apply it.
     *
     * @param propertyName the property name, in dotted form; must not be {@code null}
     * @return the environment-variable spelling
     * @throws NullPointerException if {@code propertyName} is {@code null}
     */
    static String environmentVariableFor(String propertyName) {
        Objects.requireNonNull(propertyName, "A property name is required to derive its environment "
                + "variable spelling");
        return propertyName.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
