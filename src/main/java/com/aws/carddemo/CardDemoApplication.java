/*
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
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application entry-point for the CardDemo COBOL-to-Java migration.
 *
 * <p>Provides:
 * <ul>
 *   <li>The {@code main(String[])} method that boots the Spring application
 *       context when the project's executable JAR is run via
 *       {@code java -jar carddemo-1.0.0-SNAPSHOT.jar}.</li>
 *   <li>The {@code @SpringBootConfiguration} (meta-annotated via
 *       {@code @SpringBootApplication}) that Spring Boot's test-slice
 *       bootstrappers ({@code @WebMvcTest}, {@code @DataJpaTest},
 *       {@code @SpringBootTest}, {@code @SpringBatchTest}) discover by
 *       scanning upwards from each test's package. Without this class, every
 *       Spring Boot test slice fails on context-load with
 *       {@code "Unable to find a @SpringBootConfiguration by searching
 *       packages upwards from the test"}.</li>
 *   <li>The auto-configuration root that enables Spring Boot's classpath-
 *       driven wiring of JPA, Spring MVC, Spring Security, Spring Batch,
 *       Bean Validation, AOP, and so on — all opted into via the
 *       {@code spring-boot-starter-*} dependencies declared in
 *       {@code pom.xml}.</li>
 * </ul>
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>This class has no direct COBOL counterpart. In the CICS environment the
 * "entry point" is implicit — the CICS region itself runs perpetually and
 * each user transaction (e.g., TRANID {@code CC00} for sign-on,
 * {@code CACT} for account view) is dispatched into the corresponding
 * COBOL program via the CICS transaction manager. In the Java migration the
 * Spring Boot application context plays the equivalent role: it is the
 * long-lived process that hosts the migrated controllers / services /
 * repositories that replace the CICS programs (and the embedded servlet
 * container plays the role of CICS's transaction manager, routing each HTTP
 * request to the appropriate {@code @RequestMapping} method).
 *
 * <h2>Package Placement</h2>
 *
 * <p>This class lives at the root of the {@code com.aws.carddemo} package
 * tree so that Spring Boot's {@code @SpringBootApplication}-driven component
 * scan picks up every {@code @Controller}, {@code @Service},
 * {@code @Repository}, {@code @Configuration}, and {@code @Component}
 * declared anywhere under {@code com.aws.carddemo.*} without any explicit
 * {@code @ComponentScan} configuration. Subpackages scanned by default:
 * <ul>
 *   <li>{@code com.aws.carddemo.batch.*} — Spring Batch processors</li>
 *   <li>{@code com.aws.carddemo.controller.*} — REST controllers</li>
 *   <li>{@code com.aws.carddemo.dto.*} — data-transfer objects (no
 *       Spring stereotypes; passive component-scan)</li>
 *   <li>{@code com.aws.carddemo.entity.*} — JPA entities</li>
 *   <li>{@code com.aws.carddemo.repository.*} — Spring Data JPA repositories</li>
 *   <li>{@code com.aws.carddemo.service.*} — service-layer beans</li>
 * </ul>
 *
 * <h2>Auto-Configuration Notes</h2>
 *
 * <p>The {@code @SpringBootApplication} meta-annotation enables
 * {@code @EnableAutoConfiguration} by default. The classpath dependencies
 * declared in {@code pom.xml} ({@code spring-boot-starter-web},
 * {@code -security}, {@code -data-jpa}, {@code -validation}, {@code -aop},
 * Spring Batch, Flyway) drive the auto-configuration. No customisations
 * are layered on this class — additional configuration belongs in dedicated
 * {@code @Configuration} classes under {@code com.aws.carddemo.config}
 * (subsequent migration step) so this class remains a thin bootstrap shim.
 *
 * <h2>Why a {@code main} method</h2>
 *
 * <p>The presence of a {@code public static void main(String[])} signals to
 * the Spring Boot Maven plugin that this class is the executable JAR's
 * entry-point. Once {@code spring-boot-maven-plugin}'s {@code <skip>} flag
 * is removed from {@code pom.xml} (the AAP-noted "subsequent migration"
 * action), {@code mvn package} produces a runnable JAR whose
 * {@code Main-Class} manifest attribute is the Spring Boot launcher and
 * whose {@code Start-Class} is this class.
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Spring Boot bootstrap entry-point. Loads the application context,
     * starts the embedded servlet container, and blocks until the process
     * receives a shutdown signal.
     *
     * @param args command-line arguments forwarded verbatim to Spring
     *             Boot's {@link SpringApplication#run(Class, String...)};
     *             standard Spring Boot CLI conventions apply (e.g.,
     *             {@code --server.port=8081},
     *             {@code --spring.profiles.active=prod})
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
