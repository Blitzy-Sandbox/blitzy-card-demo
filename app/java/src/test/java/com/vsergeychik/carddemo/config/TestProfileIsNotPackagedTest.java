package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the boundary between what the build ships and what only the suite reads.
 *
 * <h2>What is being guarded</h2>
 * Maven copies {@code src/main/resources} to {@code target/classes} and the Spring Boot plugin packages
 * {@code target/classes} into the distributable jar. So <em>everything</em> under
 * {@code src/main/resources} ships, including a profile-specific document that no deployment will ever
 * activate. A credential packaged into an artifact is a leaked credential (CWE-798) whether or not any
 * profile reads it: a scanner cannot tell it from one that is real, and it is the first thing anyone
 * would try against a production endpoint.
 *
 * <h2>How the two requirements are both satisfied</h2>
 * The plan places the test profile at {@code app/java/src/main/resources/application-test.yml} (AAP
 * 0.2.5, 0.3.1, 0.4.2) and gate G5 pins the declared change set to that path, so that is where the
 * document lives. What used to make that unsafe has been taken out of it and put in
 * {@code src/test/resources/carddemo-test-fixtures.yml}, which is copied to {@code target/test-classes}
 * and is never packaged:
 * <ul>
 *   <li>{@code spring.datasource.*} - the in-memory H2 URL, driver and its {@code sa} account, and the
 *       Spring Batch {@code JobRepository} schema initialisation that only makes sense against a
 *       throwaway database;</li>
 *   <li>{@code carddemo.test.fixtures.*} - the fixture inventory, including the ten plaintext
 *       {@code USRSEC} seed rows transcribed from {@code app/jcl/DUSRSECJ.jcl}.</li>
 * </ul>
 * The shipped profile reaches them through {@code spring.config.import:
 * optional:classpath:/carddemo-test-fixtures.yml}, which resolves on the test classpath and is absent
 * from the jar.
 *
 * <h2>Why this test can detect a regression at all</h2>
 * A test JVM has both {@code target/test-classes} and {@code target/classes} on its classpath, so
 * simply resolving a resource proves nothing - it would be found either way. Two things are therefore
 * asserted. First the <em>number</em> of copies: {@link ClassLoader#getResources(String)} returns one URL
 * per classpath root holding the name, and two copies of one profile document would leave Spring
 * resolving {@code classpath:/application-test.yml} to whichever root came first and silently ignoring
 * the other. Second the <em>content</em>: the packaged documents are read back and asserted to carry no
 * credential and no seed row, which is what makes the split real rather than nominal. Neither check needs
 * to know the working directory, and both hold under any runner.
 */
@DisplayName("Nothing credential-shaped is packaged into the production artifact")
class TestProfileIsNotPackagedTest {

    /** The profile document the plan places in the main tree, and which therefore ships. */
    private static final String TEST_PROFILE_RESOURCE = "application-test.yml";

    /** The profile document that must always be packaged. */
    private static final String SHIPPED_RESOURCE = "application.yml";

    /** The credentials-and-seed document that must never be packaged. */
    private static final String FIXTURE_RESOURCE = "carddemo-test-fixtures.yml";

    /**
     * Every classpath root holding the named resource.
     *
     * @param resource the resource name
     * @return one URL per root, in classpath order
     * @throws IOException if the classpath cannot be enumerated
     */
    private static List<URL> rootsHolding(String resource) throws IOException {
        return Collections.list(
                TestProfileIsNotPackagedTest.class.getClassLoader().getResources(resource));
    }

    @Nested
    @DisplayName("Where each configuration document lives")
    class Location {

        @Test
        @DisplayName("the test profile exists in exactly ONE place, the main tree the plan names")
        void theTestProfileExistsOnlyInTheMainTree() throws IOException {
            List<URL> roots = rootsHolding(TEST_PROFILE_RESOURCE);

            assertThat(roots)
                    .as("the suite cannot run without it, so it must be present")
                    .isNotEmpty();
            assertThat(roots)
                    .as("two copies of one profile document would leave Spring resolving "
                            + "classpath:/%s to whichever root came first and silently ignoring the "
                            + "other; found: %s", TEST_PROFILE_RESOURCE, roots)
                    .hasSize(1);
            assertThat(roots.get(0).toString())
                    .as("AAP 0.2.5, 0.3.1 and 0.4.2 place it under src/main/resources and gate G5 "
                            + "pins the declared change set to that path")
                    .contains("/target/classes/")
                    .doesNotContain("test-classes");
        }

        @Test
        @DisplayName("the credentials-and-seed document exists in exactly ONE place, the test tree")
        void theFixtureDocumentExistsOnlyInTheTestTree() throws IOException {
            List<URL> roots = rootsHolding(FIXTURE_RESOURCE);

            assertThat(roots)
                    .as("the shipped profile imports it, so the suite cannot run without it")
                    .isNotEmpty();
            assertThat(roots)
                    .as("two copies means one is under src/main/resources and is being packaged "
                            + "(CWE-798); found: %s", roots)
                    .hasSize(1);
            assertThat(roots.get(0).toString())
                    .as("it holds the in-memory database account and the ten USRSEC seed rows, so it "
                            + "must stay in the tree that is never packaged")
                    .contains("test-classes")
                    .doesNotContain("/target/classes/");
        }

        @Test
        @DisplayName("the shipped profile is in the main tree, where it belongs")
        void theShippedProfileIsInTheMainTree() throws IOException {
            List<URL> roots = rootsHolding(SHIPPED_RESOURCE);

            assertThat(roots).hasSize(1);
            assertThat(roots.get(0).toString())
                    .as("application.yml describes every dataset and must ship")
                    .contains("classes")
                    .doesNotContain("test-classes");
        }
    }

    @Nested
    @DisplayName("What the shipped documents are allowed to contain")
    class ShippedContent {

        /**
         * Reads one packaged configuration document.
         *
         * @param resource the resource name
         * @return its text
         * @throws IOException if it cannot be read
         */
        private String packagedDocument(String resource) throws IOException {
            URL url = rootsHolding(resource).get(0);
            try (var stream = url.openStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        /**
         * Reads the default packaged configuration document.
         *
         * @return its text
         * @throws IOException if it cannot be read
         */
        private String shippedDocument() throws IOException {
            return packagedDocument(SHIPPED_RESOURCE);
        }

        @ParameterizedTest(name = "{0} carries no credential and no seed row")
        @ValueSource(strings = {SHIPPED_RESOURCE, TEST_PROFILE_RESOURCE})
        @DisplayName("neither packaged document carries a seed credential or an embedded-database account")
        void noPackagedDocumentCarriesACredential(final String resource) throws IOException {
            // Both documents under src/main/resources are asserted, not just application.yml: the test
            // profile ships too, so the split is only real if its own text is clean.
            String packaged = packagedDocument(resource);

            assertThat(packaged)
                    .as("the ten USRSEC seed rows belong to carddemo-test-fixtures.yml alone")
                    .doesNotContain("ADMIN001")
                    .doesNotContain("PASSWORDA")
                    .doesNotContain("PASSWORDU")
                    .doesNotContain("USER0001LAWRENCE");
            assertThat(packaged)
                    .as("the in-memory database and its account belong to carddemo-test-fixtures.yml alone")
                    .doesNotContain("jdbc:h2:mem")
                    .doesNotContain("org.h2.Driver")
                    .doesNotContain("username: sa");
        }

        @Test
        @DisplayName("the packaged test profile reaches its credentials through an OPTIONAL import")
        void thePackagedTestProfileImportsThemOptionally() throws IOException {
            String profile = packagedDocument(TEST_PROFILE_RESOURCE);

            // "optional:" is the whole mechanism. Inside the jar the imported document is absent, so
            // activating the profile there configures no DataSource and the context does not start -
            // which is strictly better than starting against a credential the artifact carried.
            assertThat(profile)
                    .as("the profile must import the test-only half rather than restate it")
                    .contains("import: optional:classpath:/" + FIXTURE_RESOURCE);
        }

        @Test
        @DisplayName("the packaged test profile asks for no DDL, so none can run from the artifact")
        void thePackagedTestProfileAsksForNoDdl() throws IOException {
            // Gate G44. initialize-schema travels with the throwaway database it applies to, so the
            // packaged profile cannot ask Spring Batch to create tables against a site's backend.
            assertThat(packagedDocument(TEST_PROFILE_RESOURCE))
                    .doesNotContain("initialize-schema: always")
                    .doesNotContain("initialize-schema: embedded");
        }

        @Test
        @DisplayName("no packaged document binds a dataset to a filesystem path")
        void noPackagedDocumentBindsADatasetToAPath() throws IOException {
            // A dsname becomes a delimited SQL identifier, so a path could never be read. The
            // job-scoped overrides are the ones a global override cannot reach, and they were the ones
            // that carried paths.
            assertThat(packagedDocument(TEST_PROFILE_RESOURCE))
                    .as("every dsname is a z/OS dataset name, in both profiles")
                    .doesNotContain("dsname: ${carddemo.test.work-dir}")
                    .doesNotContain("dsname: /")
                    .doesNotContain("dsname: classpath:");
        }

        @Test
        @DisplayName("its datasource credentials are environment placeholders, never literals")
        void itsCredentialsArePlaceholders() throws IOException {
            String shipped = shippedDocument();

            // The whole point of the shipped document: it states WHICH environment variable supplies
            // each value and never a value itself, so the artifact carries no credential to leak.
            assertThat(shipped)
                    .contains("${CARDDEMO_DATASOURCE_USERNAME:")
                    .contains("${CARDDEMO_DATASOURCE_PASSWORD:")
                    .contains("${CARDDEMO_DATASOURCE_URL:");
        }

        @Test
        @DisplayName("it never initialises a schema, so no DDL can run from the artifact")
        void itNeverInitialisesASchema() throws IOException {
            // Gate G44: no DDL, no migration. A packaged document that asked Spring Batch to create its
            // tables would be issuing DDL against the site's backend on first start.
            assertThat(shippedDocument())
                    .contains("initialize-schema: never")
                    .doesNotContain("initialize-schema: always")
                    .doesNotContain("initialize-schema: embedded");
        }
    }
}
