package com.vsergeychik.carddemo;

import com.vsergeychik.carddemo.config.BatchConfig;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.util.StringUtils;

/**
 * Spring Boot entry point for the CardDemo COBOL-to-Java migration, and the composition root of
 * this module.
 *
 * <p>The fully-qualified name {@code com.vsergeychik.carddemo.CardDemoApplication} is a published
 * contract rather than an internal convention. {@code app/java/pom.xml} pins it as the
 * {@code spring-boot-maven-plugin} {@code mainClass}, so it is the {@code Start-Class} of the
 * repackaged jar and the target of {@code spring-boot:run}, and {@code docs/project-guide.md} names
 * it in the module's entry-point inventory. Maven holds that value as a plain string, outside the
 * compiler's symbol graph, so renaming this class or moving it out of this package would leave the
 * build green and the jar unbootable. This package is also the root of every type the migration
 * generates, which is why the segment above it deliberately holds no code.
 *
 * <p>The eleven packages named below are this module's package assignment: the two foundation
 * packages first, then the nine domain packages that hold the twenty-eight translated programs.
 * Spring would scan them without being told, since all eleven sit beneath this package, so the list
 * is redundant to the framework and deliberate for the reader: it states the assignment in the one
 * file that defines the module's shape, and it keeps a package created outside that set out of
 * component scope rather than silently in it.
 *
 * <p>{@code src/main/resources/application.yml} sets {@code spring.batch.job.enabled: false}, so
 * starting this application runs no batch job. Jobs are launched explicitly, one at a time, exactly
 * as JCL submits one {@code EXEC PGM=} step at a time; this matters because one of the module's
 * jobs is translated from a program that no JCL invokes anywhere and must stay runnable while
 * having no trigger. Nothing here contradicts that property: this class launches nothing, registers
 * nothing that launches anything, and does not restate the property in code. Batch support arrives
 * through auto-configuration and is configured in {@code config/BatchConfig}, which is where it
 * belongs.
 *
 * <h2>The one decision taken here: which kind of process is starting</h2>
 * <p>A JCL submission and the online service are two different processes, and only this class runs
 * early enough to tell them apart. Spring Boot fixes the application type <em>before</em> the
 * context exists - a servlet context binds the HTTP port during refresh - so the choice cannot be
 * made by a bean. Left undecided, the servlet container on the classpath wins by deduction and
 * every submission becomes a web application that happens to run a job: a port already in use
 * fails a job that has nothing to do with HTTP, and a job that succeeds briefly serves an online
 * surface nobody asked for. Neither is what {@code EXEC PGM=} means.
 *
 * <p>So the presence of {@value BatchConfig.JclJobLauncher#JOB_NAME_PROPERTY} - the same property
 * that brings {@code BatchConfig.JclJobLauncher} into existence, and the equivalent of submitting
 * one {@code EXEC PGM=} step - selects {@link WebApplicationType#NONE}, and its absence selects
 * {@link WebApplicationType#SERVLET}. The two halves then agree: a submission is a one-shot process
 * from the moment it is launched, and it ends through the launcher's terminator with the job's
 * {@code RETURN-CODE} as the process exit code (gate G35).
 *
 * <p>The property is looked for exactly where the launcher's own {@code @ConditionalOnProperty}
 * would find it, so the two can never disagree: on the command line as {@code --carddemo.batch
 * .job-name=...}, as the system property of the same name, or as the relaxed environment-variable
 * spelling {@code CARDDEMO_BATCH_JOB_NAME}, which Spring's own environment property source maps
 * back to the canonical name. On the command line its mere presence counts, because an operator who
 * typed the flag is submitting a job even if the value is empty - and an empty value must fail
 * without having bound a port. Out of band, text is required: an environment variable exported
 * empty is not a submission.
 *
 * <p>An explicit {@code spring.main.web-application-type} still overrides the choice, and that is
 * deliberate rather than accidental: Spring Boot binds {@code spring.main.*} onto the application
 * after this setter has run, so an operator who states the mode outright is obeyed.
 *
 * @see org.springframework.boot.SpringApplication
 * @see BatchConfig.JclJobLauncher#JOB_NAME_PROPERTY
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
     * Starts the application context, as the online service or as a one-shot JCL submission.
     *
     * @param args the command-line arguments, passed through to Spring Boot unchanged so that the
     *     standard property, profile and job-parameter arguments behave as documented
     */
    public static void main(String[] args) {
        springApplicationFor(args, CardDemoApplication::processValueOf).run(args);
    }

    /**
     * The application {@link #main(String[])} runs, with its process kind already chosen.
     *
     * <p>Separated from {@code main} so the choice can be read - and tested - without starting
     * anything. Nothing else is configured on it: no default properties, no additional profiles, no
     * listeners. Everything else about this application is stated in {@code application.yml} or in
     * {@code config/}, which is where a reader looks for it.
     *
     * @param args          the command-line arguments as received
     * @param processValues resolves a property name outside the command line, which is the seam a
     *                      test supplies instead of the real system properties and environment
     * @return the application, ready to run
     */
    static SpringApplication springApplicationFor(String[] args, UnaryOperator<String> processValues) {
        SpringApplication application = new SpringApplication(CardDemoApplication.class);
        application.setWebApplicationType(webApplicationTypeFor(args, processValues));
        return application;
    }

    /**
     * The kind of process being started: non-web for a JCL submission, servlet for the online
     * service.
     *
     * @param args          the command-line arguments as received
     * @param processValues resolves a property name outside the command line
     * @return {@link WebApplicationType#NONE} for a submission, {@link WebApplicationType#SERVLET}
     *         otherwise
     */
    static WebApplicationType webApplicationTypeFor(String[] args,
            UnaryOperator<String> processValues) {
        return isJclSubmission(args, processValues)
                ? WebApplicationType.NONE
                : WebApplicationType.SERVLET;
    }

    /**
     * Whether this invocation submits one job, which is the equivalent of one {@code EXEC PGM=} step.
     *
     * @param args          the command-line arguments as received
     * @param processValues resolves a property name outside the command line
     * @return {@code true} when the job-name property is present on the command line, or holds text
     *         outside it
     */
    static boolean isJclSubmission(String[] args, UnaryOperator<String> processValues) {
        return new SimpleCommandLinePropertySource(args)
                        .containsProperty(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY)
                || StringUtils.hasText(
                        processValues.apply(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY));
    }

    /**
     * The value of a property outside the command line: the system property first, then the
     * environment.
     *
     * <p>That order is Spring's own - {@code systemProperties} precedes
     * {@code systemEnvironment} in a standard environment - so the mode chosen here cannot disagree
     * with the property the context will resolve.
     *
     * @param propertyName the canonical property name
     * @return the value, or {@code null} when neither source carries it
     */
    static String processValueOf(String propertyName) {
        String systemProperty = System.getProperty(propertyName);
        return StringUtils.hasText(systemProperty)
                ? systemProperty
                : System.getenv(environmentVariableFor(propertyName));
    }

    /**
     * The relaxed environment-variable spelling of a canonical property name.
     *
     * <p>Upper case, with every separator replaced by an underscore, which is the mapping Spring's
     * own environment property source applies in the other direction. So
     * {@code carddemo.batch.job-name} is read from {@code CARDDEMO_BATCH_JOB_NAME}, and an operator
     * setting the variable a container makes convenient gets the same behaviour as one passing the
     * flag.
     *
     * @param propertyName the canonical property name
     * @return the environment-variable spelling of that name
     */
    static String environmentVariableFor(String propertyName) {
        return propertyName.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
