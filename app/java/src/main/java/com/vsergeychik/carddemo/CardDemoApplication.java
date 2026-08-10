package com.vsergeychik.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
     * Starts the application context.
     *
     * @param args the command-line arguments, passed through to Spring Boot unchanged so that the
     *     standard property, profile and job-parameter arguments behave as documented
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
