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

/**
 * Guards the boundary between what the build ships and what only the suite reads.
 *
 * <h2>What went wrong</h2>
 * {@code application-test.yml} lived under {@code src/main/resources}. Maven copies that directory to
 * {@code target/classes} and the Spring Boot plugin packages {@code target/classes} into the
 * distributable jar, so the file's datasource username and password and its ten plaintext {@code USRSEC}
 * seed passwords were shipped inside the production artifact - CWE-798, hard-coded credentials.
 *
 * <p>Nothing required them there. The file is read only when the {@code test} profile is active, which
 * happens in this suite and in the documented local run, and that run is invoked with
 * {@code -Dspring-boot.run.useTestClasspath=true}, so it reaches the test classpath regardless.
 *
 * <h2>Why the values being harmless is not the point</h2>
 * The credentials are H2's conventional in-memory account and the seed rows are transcribed verbatim
 * from {@code app/jcl/DUSRSECJ.jcl}, so neither is a real secret. That justifies keeping them in the
 * repository. It does not justify packaging them into a deployable artifact, where a scanner cannot tell
 * them from credentials that are real, and where they are the first thing anyone would try against a
 * production endpoint.
 *
 * <h2>Why this test can detect it at all</h2>
 * A test JVM has both {@code target/test-classes} and {@code target/classes} on its classpath, so
 * simply resolving the resource proves nothing - it would be found either way. What distinguishes the
 * two states is the <em>number</em> of copies: {@link ClassLoader#getResources(String)} returns one URL
 * per classpath root that holds the name. One means the file lives in exactly one place; two means a
 * copy is back under {@code src/main/resources} and is being packaged again. That check needs no
 * knowledge of the working directory and holds under any runner.
 */
@DisplayName("The test profile is not packaged into the production artifact")
class TestProfileIsNotPackagedTest {

    /** The profile document that must never be packaged. */
    private static final String TEST_PROFILE_RESOURCE = "application-test.yml";

    /** The profile document that must always be packaged. */
    private static final String SHIPPED_RESOURCE = "application.yml";

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
        @DisplayName("the test profile exists in exactly ONE place, and it is the test tree")
        void theTestProfileExistsOnlyInTheTestTree() throws IOException {
            List<URL> roots = rootsHolding(TEST_PROFILE_RESOURCE);

            assertThat(roots)
                    .as("the suite cannot run without it, so it must be present")
                    .isNotEmpty();
            assertThat(roots)
                    .as("two copies means one is back under src/main/resources and is being "
                            + "packaged again (CWE-798); found: %s", roots)
                    .hasSize(1);
            assertThat(roots.get(0).toString())
                    .as("the single copy must be the test-tree one")
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
    @DisplayName("What the shipped document is allowed to contain")
    class ShippedContent {

        /**
         * Reads the one packaged configuration document.
         *
         * @return its text
         * @throws IOException if it cannot be read
         */
        private String shippedDocument() throws IOException {
            URL url = rootsHolding(SHIPPED_RESOURCE).get(0);
            try (var stream = url.openStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        @Test
        @DisplayName("it carries no seed credential and no embedded-database account")
        void itCarriesNoCredential() throws IOException {
            String shipped = shippedDocument();

            assertThat(shipped)
                    .as("the ten USRSEC seed rows belong to the test profile alone")
                    .doesNotContain("ADMIN001")
                    .doesNotContain("PASSWORDA")
                    .doesNotContain("PASSWORDU")
                    .doesNotContain("USER0001LAWRENCE");
            assertThat(shipped)
                    .as("the in-memory database and its account belong to the test profile alone")
                    .doesNotContain("jdbc:h2:mem")
                    .doesNotContain("org.h2.Driver")
                    .doesNotContain("username: sa");
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
