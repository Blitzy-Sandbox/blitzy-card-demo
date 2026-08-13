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
 * Spring Boot entry point for the CardDemo COBOL-to-Java migration, and the composition root of this
 * module.
 *
 * <p>Jobs are launched explicitly, one at a time, exactly as JCL submits one {@code EXEC PGM=} step at a
 * time; this matters because one of the module's jobs is translated from a program that no JCL invokes
 * anywhere and must stay runnable while having no trigger.
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
     * @param args the command-line arguments, passed through to Spring Boot unchanged so that the standard
     *     property, profile and job-parameter arguments behave as documented
     */
    public static void main(String[] args) {
        springApplicationFor(args, CardDemoApplication::processValueOf).run(args);
    }

    static SpringApplication springApplicationFor(String[] args, UnaryOperator<String> processValues) {
        SpringApplication application = new SpringApplication(CardDemoApplication.class);
        application.setWebApplicationType(webApplicationTypeFor(args, processValues));
        return application;
    }

    static WebApplicationType webApplicationTypeFor(String[] args,
            UnaryOperator<String> processValues) {
        return isJclSubmission(args, processValues)
                ? WebApplicationType.NONE
                : WebApplicationType.SERVLET;
    }

    static boolean isJclSubmission(String[] args, UnaryOperator<String> processValues) {
        return new SimpleCommandLinePropertySource(args)
                        .containsProperty(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY)
                || StringUtils.hasText(
                        processValues.apply(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY));
    }

    static String processValueOf(String propertyName) {
        String systemProperty = System.getProperty(propertyName);
        return StringUtils.hasText(systemProperty)
                ? systemProperty
                : System.getenv(environmentVariableFor(propertyName));
    }

    static String environmentVariableFor(String propertyName) {
        return propertyName.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
