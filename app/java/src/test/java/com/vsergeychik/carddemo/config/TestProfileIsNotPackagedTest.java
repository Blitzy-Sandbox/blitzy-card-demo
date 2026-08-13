package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vsergeychik.carddemo.common.ConversationStateSeal;
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
 */
@DisplayName("Nothing credential-shaped is packaged into the production artifact")
class TestProfileIsNotPackagedTest {
    private static final String TEST_PROFILE_RESOURCE = "application-test.yml";

    private static final String SHIPPED_RESOURCE = "application.yml";

    private static final String FIXTURE_RESOURCE = "carddemo-test-fixtures.yml";

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
                            + "other. A copy under target/test-classes is a STALE BUILD ARTIFACT, not a "
                            + "source file: this document lived in src/test/resources until it was moved "
                            + "to src/main/resources, and a target/ directory produced before that move "
                            + "still holds the orphan because `test` alone never deletes it. It wins the "
                            + "classpath because Surefire puts target/test-classes ahead of "
                            + "target/classes, and because the orphan predates the "
                            + "carddemo-test-fixtures.yml split it imports no datasource - which fails "
                            + "every configuration-bound test in the suite at once. RUN `mvn -f "
                            + "app/java/pom.xml clean test` (or clean verify); this failure is the "
                            + "explanation for the others. Found: %s",
                            TEST_PROFILE_RESOURCE, roots)
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
        private String packagedDocument(String resource) throws IOException {
            URL url = rootsHolding(resource).get(0);
            try (var stream = url.openStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private String shippedDocument() throws IOException {
            return packagedDocument(SHIPPED_RESOURCE);
        }

        @ParameterizedTest(name = "{0} carries no credential and no seed row")
        @ValueSource(strings = {SHIPPED_RESOURCE, TEST_PROFILE_RESOURCE})
        @DisplayName("neither packaged document carries a seed credential or an embedded-database account")
        void noPackagedDocumentCarriesACredential(final String resource) throws IOException {
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

            assertThat(profile)
                    .as("the profile must import the test-only half rather than restate it")
                    .contains("import: optional:classpath:/" + FIXTURE_RESOURCE);
        }

        @Test
        @DisplayName("the packaged test profile asks for no DDL, so none can run from the artifact")
        void thePackagedTestProfileAsksForNoDdl() throws IOException {
            assertThat(packagedDocument(TEST_PROFILE_RESOURCE))
                    .doesNotContain("initialize-schema: always")
                    .doesNotContain("initialize-schema: embedded");
        }

        @Test
        @DisplayName("no packaged document binds a dataset to a filesystem path")
        void noPackagedDocumentBindsADatasetToAPath() throws IOException {
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

            assertThat(shipped)
                    .contains("${CARDDEMO_DATASOURCE_USERNAME:")
                    .contains("${CARDDEMO_DATASOURCE_PASSWORD:")
                    .contains("${CARDDEMO_DATASOURCE_URL:");
        }

        @Test
        @DisplayName("its conversation-state secret is an environment placeholder with no default, and "
                + "the pinned test value is nowhere in it")
        void itsConversationStateSecretIsARequiredPlaceholder() throws IOException {
            String shipped = shippedDocument();

            // This key is what makes WS-THIS-PROGCOMMAREA unforgeable while it travels in the payload, so
            // a literal in the shipped document would be a forgeable key in every deployment that took the
            // artifact as it came. It is stated with no ':' default on purpose: a deployment that names
            // none must fail at startup rather than seal with something this module chose.
            assertThat(shipped)
                    .contains("${" + ConversationStateSeal.SECRET_ENVIRONMENT_VARIABLE + "}")
                    .doesNotContain("${" + ConversationStateSeal.SECRET_ENVIRONMENT_VARIABLE + ":");
            assertThat(shipped)
                    .as("the test profile's pinned value must not leak into the document that ships")
                    .doesNotContain("test-profile-conversation-state-seal-key");
        }

        @Test
        @DisplayName("it never initialises a schema, so no DDL can run from the artifact")
        void itNeverInitialisesASchema() throws IOException {
            assertThat(shippedDocument())
                    .contains("initialize-schema: never")
                    .doesNotContain("initialize-schema: always")
                    .doesNotContain("initialize-schema: embedded");
        }
    }
}
