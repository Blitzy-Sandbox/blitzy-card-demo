package com.vsergeychik.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
     * Boots the application context.
     *
     * @param args the command-line arguments, passed through to Spring Boot unchanged so that the
     *     standard property, profile and job-parameter arguments behave as documented
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
