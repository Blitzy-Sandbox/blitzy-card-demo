package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.transaction.ReportRequestController;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobDatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler.CobolErrorResponse;
import com.vsergeychik.carddemo.config.WebConfig.JobSubmissionProperties;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Decision-level tests for the configuration classes {@link CobolCharsetConfig}, {@link DataSourceConfig},
 * {@link BatchConfig} and {@link WebConfig}.
 */
@DisplayName("config - the startup guards that refuse a silently wrong default")
class ConfigBranchCoverageTest {
    private static final String LOCATION = "SENTINEL.CATALOG.ENTRY";

    /**
     * A JCL {@code EXEC PGM=} step, as the job-contract group reads it out of the legacy decks.
     *
     * <p>Deliberately permissive about the surrounding syntax and strict about nothing else: JCL allows
     * any run of blanks around {@code EXEC} and the operand may be followed by a comma, a blank or the
     * end of the line, and a pattern that assumed one spelling would silently find fewer steps than the
     * deck contains - which would make the derived job count agree with this module for the wrong
     * reason. Case-insensitive because the decks are upper case but the filename extensions in this
     * repository are not consistently either case.
     */
    private static final Pattern EXEC_PGM =
            Pattern.compile("EXEC\\s+PGM\\s*=\\s*([A-Z0-9$#@]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Where the build puts {@code app/jcl} and {@code app/proc} on the test classpath.
     *
     * <p>Both directories land under the one target path, which is what lets the derivation treat the
     * estate as a single set of decks: {@code CBTRN03C} is invoked from {@code app/jcl/TRANREPT.jcl}
     * and again from {@code app/proc/TRANREPT.prc}, and those two sites are one job, so nothing is
     * gained by keeping the two directories apart here. No deck name collides across them.
     */
    private static final String JCL_ORACLE_RESOURCE = "/jcl-oracle";

    /**
     * What makes a {@code "default"} arm of the two {@code runnerFor} helpers below actually read
     * {@code application.yml} alone.
     *
     * <p>An {@link ApplicationContextRunner} builds its environment on top of the surrounding JVM's
     * system properties and process environment. A build invoked as
     * {@code mvn test -Dspring.profiles.active=test}, or run by a CI executor that exports
     * {@code SPRING_PROFILES_ACTIVE=test} - a common convention - therefore activates the {@code test}
     * profile <em>inside</em> a runner that asked for no profile: {@code application-test.yml} loads on
     * top of the document under test and its values win. Two things then go wrong at once. The
     * {@code default}/{@code test} parameterised pairs in {@link TheShippedConfigurationDocuments}
     * become the same document twice, so the default document stops being tested at all and nothing
     * fails to say so; and
     * {@link TheConfigurationPackageWiresUp#theDefaultProfileRefusesToStartWithoutAUrl()} starts
     * instead of refusing, because it finds the test profile's own URL.
     *
     * <p>Stating the key with an empty value closes both: {@code withPropertyValues} installs it as the
     * first property source, ahead of {@code systemProperties} and {@code systemEnvironment}, and an
     * empty value means no active profile. Clearing the system property instead was measured and
     * rejected - it neutralises the {@code -D} form and leaves the environment-variable form leaking.
     * Nothing global is mutated, so no slice perturbs another (practice B7).
     */
    private static final String NO_ACTIVE_PROFILE = "";

    private static final String ACTIVE_PROFILE_PROPERTY = "spring.profiles.active=";

    private static final String DEFAULT_PROFILE = "default";

    private static ApplicationContextInitializer<ConfigurableApplicationContext>
            processEnvironmentActivating(String profile) {
        return context -> context.getEnvironment().getPropertySources().addBefore(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("simulatedProcessEnvironment",
                        Map.of("SPRING_PROFILES_ACTIVE", profile)));
    }

    @Nested
    @DisplayName("CobolCharsetConfig.resolve - never substitutes the platform default")
    class CharsetResolution {
        @Test
        @DisplayName("a supported charset name resolves to that charset")
        void supportedNameResolves() {
            assertThat(CobolCharsetConfig.resolve("US-ASCII",
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY))
                    .isEqualTo(Charset.forName("US-ASCII"));
            assertThat(CobolCharsetConfig.resolve("IBM037",
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .isEqualTo(Charset.forName("IBM037"));
        }

        @Test
        @DisplayName("an unsupported name fails, naming the property and never falling back")
        void unsupportedNameFails() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve("NO-SUCH-CODE-PAGE",
                            CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .withMessageContaining("NO-SUCH-CODE-PAGE")
                    .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .withMessageContaining("platform default charset is never substituted");
        }

        @Test
        @DisplayName("a blank or null name fails as a configuration error naming the key")
        void blankOrNullNameFails() {
            for (String blank : new String[] { null, "", "   " }) {
                assertThatIllegalStateException()
                        .isThrownBy(() -> CobolCharsetConfig.resolve(blank,
                                CobolCharsetConfig.DATASET_CHARSET_PROPERTY))
                        .withMessageContaining(CobolCharsetConfig.DATASET_CHARSET_PROPERTY)
                        .withMessageContaining("required and is never defaulted");
            }
        }

        @Test
        @DisplayName("a syntactically illegal name is wrapped, keeping the JDK failure as the cause")
        void syntacticallyIllegalNameIsWrapped() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve("IBM 037",
                            CobolCharsetConfig.ASCII_CHARSET_PROPERTY))
                    .withMessageContaining("IBM 037")
                    .withMessageContaining(CobolCharsetConfig.ASCII_CHARSET_PROPERTY)
                    .withMessageContaining("not a syntactically legal charset name")
                    .withCauseInstanceOf(IllegalCharsetNameException.class);
        }

        @Test
        @DisplayName("the three declared charset beans each resolve from their own property")
        void beansResolveIndependently() {
            CobolCharsetConfig config = new CobolCharsetConfig("IBM037", "US-ASCII", "US-ASCII");

            assertThat(config.carddemoEbcdicCharset()).isEqualTo(Charset.forName("IBM037"));
            assertThat(config.carddemoAsciiCharset()).isEqualTo(Charset.forName("US-ASCII"));
            assertThat(config.carddemoDatasetCharset()).isEqualTo(Charset.forName("US-ASCII"));
        }

        @Test
        @DisplayName("a bad name reaches the failure through the bean method too")
        void beanMethodPropagatesTheFailure() {
            CobolCharsetConfig config =
                    new CobolCharsetConfig("NOT-A-CHARSET", "US-ASCII", "US-ASCII");

            assertThatIllegalStateException()
                    .isThrownBy(config::carddemoEbcdicCharset)
                    .withMessageContaining("NOT-A-CHARSET");
        }
    }

    @Nested
    @DisplayName("DataSourceConfig.dataSource - the JDBC URL is a deployment-time input")
    class DataSourceGuard {
        @Test
        @DisplayName("a configured URL builds a pooled DataSource")
        void configuredUrlBuildsAPool() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("jdbc:h2:mem:carddemo-config-guard");
            properties.setDriverClassName("org.h2.Driver");

            DataSource dataSource = new DataSourceConfig().dataSource(properties);

            assertThat(dataSource).isNotNull();
            assertThat(new DataSourceConfig().jdbcTemplate(dataSource).getDataSource())
                    .isSameAs(dataSource);
        }

        @Test
        @DisplayName("an absent URL fails, and explains that no driver is pinned on purpose")
        void absentUrlFails() {
            DataSourceProperties properties = new DataSourceProperties();

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("spring.datasource.url is not configured")
                    .withMessageContaining("DEPLOYMENT-TIME INPUTS");
        }

        @Test
        @DisplayName("a blank URL is treated as absent, not as a value")
        void blankUrlFails() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("   ");

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("spring.datasource.url is not configured");
        }

        @Test
        @DisplayName("an explicitly named driver class is the one the pool receives")
        void configuredDriverClassWins() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("jdbc:h2:mem:carddemo-configured-driver");
            properties.setDriverClassName("org.h2.jdbcx.JdbcDataSource");

            assertThat(((HikariDataSource) new DataSourceConfig().dataSource(properties))
                    .getDriverClassName())
                    .isEqualTo("org.h2.jdbcx.JdbcDataSource");
        }

        @Test
        @DisplayName("a named driver class that is absent from the classpath fails at startup")
        void namedButAbsentDriverClassFails() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("jdbc:h2:mem:carddemo-absent-driver");
            properties.setDriverClassName("com.example.NoSuchMainframeDriver");

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("com.example.NoSuchMainframeDriver")
                    .withMessageContaining("is not on the classpath")
                    .withMessageContaining("spring.datasource.driver-class-name");
        }

        @Test
        @DisplayName("a driver derived from a recognised URL scheme still has to be on the classpath")
        void aDerivedDriverMustAlsoBePresent() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("jdbc:postgresql://mainframe:5432/carddemo");

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("is not on the classpath")
                    .withMessageContaining("derived from the scheme of spring.datasource.url");
        }

        @Test
        @DisplayName("an unrecognised URL scheme with no driver named fails rather than substituting "
                + "the embedded database on the classpath")
        void undeterminableDriverFailsRatherThanFallingBackToTheEmbeddedDatabase() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("jdbc:carddemo-vsam://mainframe/PROD");

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("No JDBC driver class could be determined")
                    .withMessageContaining("NO FALLBACK IS APPLIED");
        }
    }

    @Nested
    @DisplayName("DatasetBindings.validate - a catalogue that could read the wrong bytes never starts")
    class DatasetCatalogueValidation {
        @Test
        @DisplayName("the complete, coherent catalogue validates")
        void aCompleteCatalogueValidates() {
            assertThatNoException().isThrownBy(validCatalogue()::validate);
        }

        @Test
        @DisplayName("a missing DD name is reported, and an unexpected one alongside it")
        void missingAndUnexpectedKeysAreReportedTogether() {
            DatasetBindings shortOfOne = validCatalogue();
            shortOfOne.remove("TCATBALF");
            assertThatIllegalStateException().isThrownBy(shortOfOne::validate)
                    .withMessageContaining("Missing: [TCATBALF]")
                    .withMessageContaining("Unexpected: []");

            DatasetBindings misspelled = validCatalogue();
            misspelled.put("TCATBLAF", misspelled.remove("TCATBALF"));
            assertThatIllegalStateException().isThrownBy(misspelled::validate)
                    .withMessageContaining("Missing: [TCATBALF]")
                    .withMessageContaining("Unexpected: [TCATBLAF]");

            DatasetBindings withAnExtra = validCatalogue();
            withAnExtra.put("SPAREDD", entry(LOCATION, "sequential", "FB", null, 50, null));
            assertThatIllegalStateException().isThrownBy(withAnExtra::validate)
                    .withMessageContaining("Missing: []")
                    .withMessageContaining("Unexpected: [SPAREDD]");
        }

        @Test
        @DisplayName("an entry bound to nothing at all is reported rather than dereferenced")
        void aNullEntryIsReported() {
            DatasetBindings withNullEntry = validCatalogue();
            withNullEntry.put("ACCTDAT", null);

            assertThatIllegalStateException().isThrownBy(withNullEntry::validate)
                    .withMessageContaining("'ACCTDAT' is invalid")
                    .withMessageContaining("declares no properties at all");
        }

        @Test
        @DisplayName("each invalid component is reported naming the DD name and the component")
        void eachInvalidComponentIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(withAcctdat(entry("  ", "sequential", "FB", null, 50, null))
                            ::validate)
                    .withMessageContaining("declares no dsname");
            assertThatIllegalStateException()
                    .isThrownBy(withAcctdat(entry(LOCATION, "vsam", "FB", null, 50, null))::validate)
                    .withMessageContaining("its organization is 'vsam'");
            assertThatIllegalStateException()
                    .isThrownBy(withAcctdat(entry(LOCATION, null, "FB", null, 50, null))::validate)
                    .withMessageContaining("its organization is 'null'");
            assertThatIllegalStateException()
                    .isThrownBy(withAcctdat(entry(LOCATION, "sequential", "V", null, 50, null))
                            ::validate)
                    .withMessageContaining("its record-format is 'V'");
            assertThatIllegalStateException()
                    .isThrownBy(withAcctdat(entry(LOCATION, "sequential", "FB", null, 0, null))
                            ::validate)
                    .withMessageContaining("its record-length is 0");
            assertThatIllegalStateException()
                    .isThrownBy(withAcctdat(entry(LOCATION, "sequential", "FB", -1, 50, null))
                            ::validate)
                    .withMessageContaining("its block-size is -1");
            assertThatIllegalStateException()
                    .isThrownBy(withAcctdat(entry(LOCATION, "sequential", "FB", null, 50, 0))
                            ::validate)
                    .withMessageContaining("its key-length is 0");
        }

        @Test
        @DisplayName("an absent record format and a block size of zero are both legitimate")
        void absentFormatAndZeroBlockSizeAreLegitimate() {
            assertThatNoException().isThrownBy(
                    withAcctdat(entry(LOCATION, "sequential", null, 0, 50, null))::validate);

            assertThatNoException().isThrownBy(
                    withAcctdat(entry(LOCATION, "ksds", null, 0, 50, 11))::validate);
        }

        @Test
        @DisplayName("an alternate-index path must name a base and a key, and a non-path must name "
                + "neither")
        void theAlternateIndexRelationshipIsEnforced() {
            assertThatIllegalStateException()
                    .isThrownBy(withAcctdat(new DatasetBinding(LOCATION, "sequential", false, "FB",
                            null, 50, "CVACT01Y", null, null, "CARDDAT", null))::validate)
                    .withMessageContaining("names base 'CARDDAT'");
            assertThatIllegalStateException()
                    .isThrownBy(withCardaix(new DatasetBinding(LOCATION, "aix-path", false, "FB",
                            null, 50, "CVACT02Y", null, null, null, "SENTINEL-ALT-KEY"))::validate)
                    .withMessageContaining("names no base");
            assertThatIllegalStateException()
                    .isThrownBy(withCardaix(new DatasetBinding(LOCATION, "aix-path", false, "FB",
                            null, 50, "CVACT02Y", null, null, "CARDDAT", " "))::validate)
                    .withMessageContaining("names no alternate-key");
            assertThatIllegalStateException()
                    .isThrownBy(withCardaix(path("NOSUCHDD"))::validate)
                    .withMessageContaining("not itself a declared DD name");
            assertThatIllegalStateException()
                    .isThrownBy(withCardaix(path("CXACAIX"))::validate)
                    .withMessageContaining("itself an alternate-index path");
        }

        private DatasetBindings validCatalogue() {
            DatasetBindings bindings = new DatasetBindings();
            Map<String, String> bases = Map.of(
                    "CARDAIX", "CARDDAT", "CXACAIX", "CCXREF", "XREFFIL1", "CCXREF");
            for (String ddName : DatasetBindings.REQUIRED_DD_NAMES.stream().sorted().toList()) {
                String base = bases.get(ddName);
                if (base != null) {
                    bindings.put(ddName, path(base));
                } else if (bases.containsValue(ddName)) {
                    bindings.put(ddName, baseCluster());
                } else {
                    bindings.put(ddName, entry(LOCATION, "sequential", "FB", null, 50, null));
                }
            }
            return bindings;
        }

        private DatasetBindings withAcctdat(DatasetBinding binding) {
            DatasetBindings bindings = validCatalogue();
            bindings.put("ACCTDAT", binding);
            return bindings;
        }

        private DatasetBindings withCardaix(DatasetBinding binding) {
            DatasetBindings bindings = validCatalogue();
            bindings.put("CARDAIX", binding);
            return bindings;
        }

        private static DatasetBinding entry(String dsname, String organization, String recordFormat,
                Integer blockSize, int recordLength, Integer keyLength) {
            return new DatasetBinding(dsname, organization, false, recordFormat, blockSize,
                    recordLength, "CVACT01Y", keyLength, null, null, null);
        }

        private static DatasetBinding path(String base) {
            return new DatasetBinding(LOCATION, "aix-path", false, "FB", null, 50, "CVACT02Y",
                    11, 0, base, "SENTINEL-ALT-KEY");
        }

        private static DatasetBinding baseCluster() {
            return new DatasetBinding(LOCATION, "ksds", false, "FB", null, 50, "CVACT02Y",
                    11, 0, null, null);
        }
    }

    @Nested
    @DisplayName("JobContracts.validate - a job that would run the wrong work never starts")
    class JobContractValidation {
        @Test
        @DisplayName("the nine source-derived jobs, each paired with its own program, validate")
        void theNineJobsValidate() {
            assertThatNoException().isThrownBy(() -> validJobs().validate(validCatalogue()));
        }

        @Test
        @DisplayName("a missing job is reported, and an invented one is refused")
        void missingAndUnexpectedJobsAreReported() {
            JobContracts shortOfOne = validJobs();
            shortOfOne.remove("transaction-posting-job");
            assertThatIllegalStateException().isThrownBy(() -> shortOfOne.validate(validCatalogue()))
                    .withMessageContaining("Missing: [transaction-posting-job]")
                    .withMessageContaining("Unexpected: []")
                    .withMessageContaining("do not close a gap by inventing a tenth job".substring(1));

            JobContracts withATenth = validJobs();
            withATenth.put("date-utility-job", inventedJob("CSUTLDTC"));
            assertThatIllegalStateException().isThrownBy(() -> withATenth.validate(validCatalogue()))
                    .withMessageContaining("Missing: []")
                    .withMessageContaining("Unexpected: [date-utility-job]");
        }

        @Test
        @DisplayName("the refusal cites the EXEC PGM= authority and gate G12, not a preference")
        void theRefusalCitesTheSourceAuthority() {
            // A reader arrives at this diagnostic for exactly one reason: the migration plan's summary
            // total says ten. So the message has to answer that, and answering it means naming the
            // authority for nine and the gate that forbids the only two candidates for a tenth. A
            // message that merely restated the number would leave the next reader to invent the job
            // this check exists to refuse.
            JobContracts withATenth = validJobs();
            withATenth.put("statement-generation-job-b", inventedJob("CBSTM03B"));

            assertThatIllegalStateException().isThrownBy(() -> withATenth.validate(validCatalogue()))
                    .withMessageContaining("EXEC PGM= step in app/jcl or app/proc")
                    .withMessageContaining("untriggered CBTRN01C")
                    .withMessageContaining("StatementGenerationJobB for CBSTM03B")
                    .withMessageContaining("DateUtilityJob for CSUTLDTC")
                    .withMessageContaining("gate G12")
                    .withMessageContaining("No further EXEC PGM= exists to translate");
        }

        @Test
        @DisplayName("the job inventory is derived from the transcribed steps, not chosen alongside "
                + "them")
        void theJobInventoryIsDerivedFromTheTranscribedSteps() {
            // REQUIRED_JOBS and REQUIRED_STEPS are two hand-written maps over the same JCL, and a
            // tenth job invented in one of them would look coherent on its own. Deriving the program
            // set from the step transcription and comparing it against the pairing map removes that
            // freedom: a tenth job key needs a tenth transcribed step sequence naming a tenth program,
            // and the JCL has no such step to transcribe.
            List<String> programsNamedByASteppedJob = JobContracts.REQUIRED_STEPS.values().stream()
                    .flatMap(List::stream)
                    .map(StepContract::program)
                    .filter(program -> program.startsWith("CB"))
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(programsNamedByASteppedJob)
                    .as("every job's program is named by one of its own transcribed steps, and no "
                            + "CardDemo program is stepped without being a job")
                    .containsExactlyInAnyOrderElementsOf(
                            JobContracts.REQUIRED_JOBS.values().stream().sorted().toList());
            assertThat(JobContracts.REQUIRED_JOBS.values())
                    .as("nine keys, nine distinct PROGRAM-IDs - a job may not be a second run of a "
                            + "program another job already owns")
                    .doesNotHaveDuplicates()
                    .hasSize(9);
            assertThat(JobContracts.REQUIRED_JOBS.values())
                    .as("the two called subprograms carry names ending in Job and are a @Component "
                            + "and a @Service, so neither may hold a job key (gate G12)")
                    .doesNotContain("CBSTM03B", "CSUTLDTC");
        }

        @Test
        @DisplayName("the eight JCL-invoked programs are exactly what EXEC PGM= names, read from the "
                + "JCL itself")
        void theEightJclInvokedProgramsAreExactlyWhatTheJclNames() {
            // The one assertion in this suite that consults the immutable authority rather than a
            // transcription of it. It is what turns "nine because this module says nine" into "nine
            // because app/jcl and app/proc say eight and nothing invokes the ninth" - which is the
            // whole answer to the plan's summary total of ten.
            List<Path> decks = legacyJobDecks();
            List<String> invoked = decks.stream()
                    .flatMap(deck -> programsInvokedBy(deck).stream())
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(invoked)
                    .as("EXEC PGM= across app/jcl and app/proc names these CardDemo programs, and "
                            + "CBTRN03C appearing in both TRANREPT.jcl and TRANREPT.prc is two sites "
                            + "for one job")
                    .containsExactly("CBACT01C", "CBACT02C", "CBACT03C", "CBACT04C", "CBCUS01C",
                            "CBSTM03A", "CBTRN02C", "CBTRN03C");

            List<String> jobPrograms = JobContracts.REQUIRED_JOBS.values().stream().sorted().toList();
            assertThat(jobPrograms)
                    .as("every invoked program has a job, so no JCL step is unmigrated")
                    .containsAll(invoked);
            assertThat(jobPrograms.stream().filter(program -> !invoked.contains(program)).toList())
                    .as("exactly one job exists that no JCL invokes - the orphan CBTRN01C, which "
                            + "migrates all the same (gate G13)")
                    .containsExactly("CBTRN01C");
            assertThat(invoked)
                    .as("neither called subprogram has an EXEC PGM= anywhere, which is why gate G12 "
                            + "makes them a @Component and a @Service rather than jobs")
                    .doesNotContain("CBSTM03B", "CSUTLDTC");
        }

        /**
         * The JCL and cataloged-procedure decks, as classpath resources.
         *
         * <p>The build copies {@code app/jcl} and {@code app/proc} into
         * {@code target/test-classes/jcl-oracle/} through the third and fourth {@code <testResource>}
         * entries in {@code app/java/pom.xml}, so the decks arrive here the same way the area-code
         * copybook arrives at {@code AreaCodeLookupTest} - as immutable classpath resources rather
         * than as files found by guessing at the working directory. That is not tidiness: this module
         * has already had to fix one suite that located its oracle by walking up from wherever the
         * process happened to start, which made a correct transcription fail under a different
         * checkout layout. A classpath resource has no working directory and no parent to walk.
         *
         * <p>Absence is a <strong>build configuration failure</strong>, reported as one, and not a
         * reason to skip the derivation - the same stance {@code AreaCodeLookupTest} takes for the
         * same reason: a check that quietly stops running is worse than one that fails.
         *
         * <p>Reading rather than writing keeps practice B3 intact: {@code app/jcl} and
         * {@code app/proc} are read-only parity reference, the build copies out of them, and nothing
         * here can write back.
         *
         * @return every deck under {@code jcl-oracle/}, in stable order
         */
        private static List<Path> legacyJobDecks() {
            URL oracle = ConfigBranchCoverageTest.class.getResource(JCL_ORACLE_RESOURCE);
            if (oracle == null) {
                throw new IllegalStateException("The parity oracle " + JCL_ORACLE_RESOURCE
                        + " is absent from the test classpath. It is app/jcl and app/proc, copied "
                        + "there by the third and fourth <testResource> entries in app/java/pom.xml, "
                        + "and the batch job count is derived by counting EXEC PGM= across those "
                        + "decks rather than against this module's transcription of them - so its "
                        + "absence is a build configuration failure and not a reason to skip the "
                        + "check.");
            }
            if (!"file".equals(oracle.getProtocol())) {
                throw new IllegalStateException("The parity oracle " + JCL_ORACLE_RESOURCE + " is on "
                        + "the classpath as " + oracle + ", which is not a directory this suite can "
                        + "enumerate. Surefire puts target/test-classes on the classpath as a "
                        + "directory; run the tests through Maven rather than from a packaged "
                        + "artifact.");
            }
            Path directory;
            try {
                directory = Path.of(oracle.toURI());
            } catch (URISyntaxException notAUsablePath) {
                throw new IllegalStateException("The parity oracle " + JCL_ORACLE_RESOURCE + " is on "
                        + "the classpath at " + oracle + ", which is not a usable path",
                        notAUsablePath);
            }
            List<Path> decks;
            try (Stream<Path> entries = Files.list(directory)) {
                decks = entries.filter(Files::isRegularFile).sorted().toList();
            } catch (IOException cannotList) {
                throw new UncheckedIOException(directory + " could not be listed, so the job "
                        + "inventory cannot be derived from the JCL", cannotList);
            }
            // A completeness claim over an empty set is not a claim at all, so an empty oracle
            // directory has to fail here rather than let every assertion below pass vacuously.
            assertThat(decks)
                    .as("app/jcl holds 29 job decks and app/proc holds 2 cataloged procedures, all "
                            + "copied onto the test classpath, and the derivation needs every one of "
                            + "them")
                    .hasSize(31);
            return decks;
        }

        /**
         * The CardDemo programs one deck invokes with {@code EXEC PGM=}.
         *
         * <p>Utility programs are excluded by the {@code CB} prefix rather than by an exclusion list:
         * every batch program this migration owns is named {@code CBxxxxxC}, and {@code IDCAMS},
         * {@code IEFBR14}, {@code SORT} and {@code SDSF} are step programs the JCL runs but the
         * migration does not translate - {@code CREASTMT.JCL}'s delete/define and REPRO steps and
         * {@code TRANREPT}'s sort are modelled as steps of their job, not as jobs.
         *
         * @param deck the JCL or procedure file to scan
         * @return the distinct CardDemo program names it invokes, in encounter order
         */
        private static List<String> programsInvokedBy(Path deck) {
            String text;
            try {
                text = Files.readString(deck, StandardCharsets.ISO_8859_1);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(deck + " could not be read, so the job inventory "
                        + "cannot be derived from the JCL", unreadable);
            }
            List<String> invoked = new ArrayList<>();
            Matcher steps = EXEC_PGM.matcher(text);
            while (steps.find()) {
                String program = steps.group(1).toUpperCase(Locale.ROOT);
                if (program.startsWith("CB") && !invoked.contains(program)) {
                    invoked.add(program);
                }
            }
            return invoked;
        }

        @Test
        @DisplayName("a job re-pointed at another program is refused, because the pairing is fixed by "
                + "the source")
        void aJobRePointedAtAnotherProgramIsRefused() {
            JobContracts rePointed = validJobs();
            rePointed.put("account-balance-job", new JobContract("CBACT02C", List.of(),
                    JobContracts.REQUIRED_STEPS.get("account-balance-job"), null, Map.of()));

            assertThatIllegalStateException().isThrownBy(() -> rePointed.validate(validCatalogue()))
                    .withMessageContaining("'account-balance-job' is invalid")
                    .withMessageContaining("declares program 'CBACT02C'")
                    .withMessageContaining("translated from CBACT01C");
        }

        @Test
        @DisplayName("a job that declares nothing at all is reported rather than dereferenced")
        void aNullContractIsReported() {
            JobContracts withNull = validJobs();
            withNull.put("account-balance-job", null);

            assertThatIllegalStateException().isThrownBy(() -> withNull.validate(validCatalogue()))
                    .withMessageContaining("declares no properties at all");
        }

        @Test
        @DisplayName("an absent, incomplete, duplicated or wrongly gated step sequence is refused")
        void theStepSequenceIsValidated() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-balance-job",
                            new JobContract("CBACT01C", List.of(), List.of(), null, Map.of()))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares no steps");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-balance-job", new JobContract("CBACT01C",
                            List.of(), List.of(new StepContract(" ", "CBACT01C", false)), null,
                            Map.of())).validate(validCatalogue()))
                    .withMessageContaining("its step at position 0 declares no value for "
                            + "carddemo.jobs.account-balance-job.steps[0].name");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-balance-job", new JobContract("CBACT01C",
                            List.of(), List.of(new StepContract("STEP05", "CBACT01C", false),
                                    new StepContract("STEP05", "CBACT01C", false)), null, Map.of()))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares two steps named 'STEP05'");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-balance-job", new JobContract("CBACT01C",
                            List.of(), List.of(new StepContract("STEP05", "CBACT01C", true)), null,
                            Map.of())).validate(validCatalogue()))
                    .withMessageContaining("but it has no preceding step");
        }

        @Test
        @DisplayName("the exact ordered step tuple is required: a step added, dropped, reordered, "
                + "re-pointed or gated differently is refused")
        void theStepSequenceMustBeExactlyTheOneTheJclDeclares() {
            String statementJob = "statement-generation-job-a";
            List<StepContract> shipped = JobContracts.REQUIRED_STEPS.get(statementJob);

            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithSteps(statementJob, withoutStepNamed(shipped, "STEP030"))
                            .validate(validCatalogue()))
                    .withMessageContaining("its step sequence is not the one its JCL declares")
                    .withMessageContaining("configured: [DELDEF01/IDCAMS, STEP010/SORT, "
                            + "STEP020/IDCAMS [COND=(0,NE)], STEP040/CBSTM03A [COND=(0,NE)]]")
                    .withMessageContaining("required:   [DELDEF01/IDCAMS, STEP010/SORT, "
                            + "STEP020/IDCAMS [COND=(0,NE)], STEP030/IEFBR14 [COND=(0,NE)], "
                            + "STEP040/CBSTM03A [COND=(0,NE)]]");

            List<StepContract> withASixth = new ArrayList<>(shipped);
            withASixth.add(new StepContract("STEP050", "CBSTM03A", true));
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithSteps(statementJob, withASixth)
                            .validate(validCatalogue()))
                    .withMessageContaining("STEP050/CBSTM03A [COND=(0,NE)]")
                    .withMessageContaining("A step added, removed, reordered, re-pointed at another "
                            + "program or gated differently");

            List<StepContract> reordered = new ArrayList<>(shipped);
            Collections.swap(reordered, 1, 2);
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithSteps(statementJob, reordered)
                            .validate(validCatalogue()))
                    .withMessageContaining("configured: [DELDEF01/IDCAMS, "
                            + "STEP020/IDCAMS [COND=(0,NE)], STEP010/SORT, ")
                    .withMessageContaining("reordering CREASTMT's sort and its REPRO loads the "
                            + "previous run's data");

            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithSteps(statementJob,
                            withProgramOfStepNamed(shipped, "STEP010", "IDCAMS"))
                            .validate(validCatalogue()))
                    .withMessageContaining("configured: [DELDEF01/IDCAMS, STEP010/IDCAMS, ");

            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithSteps(statementJob,
                            withGateOfStepNamed(shipped, "STEP040", false))
                            .validate(validCatalogue()))
                    .withMessageContaining("ungating STEP040 generates statements from a work file "
                            + "the load never populated");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithSteps(statementJob,
                            withGateOfStepNamed(shipped, "STEP010", true))
                            .validate(validCatalogue()))
                    .withMessageContaining("STEP010/SORT [COND=(0,NE)]");

            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithSteps("account-balance-reader-job",
                            List.of(new StepContract("STEP15", "CBACT02C", false)))
                            .validate(validCatalogue()))
                    .withMessageContaining("configured: [STEP15/CBACT02C]")
                    .withMessageContaining("required:   [STEP05/CBACT02C]");
        }

        @Test
        @DisplayName("every required job has a transcribed step sequence, so no job can escape the "
                + "tuple check")
        void everyRequiredJobHasATranscribedSequence() {
            assertThat(JobContracts.REQUIRED_STEPS.keySet())
                    .containsExactlyInAnyOrderElementsOf(JobContracts.REQUIRED_JOBS.keySet());
            JobContracts.REQUIRED_STEPS.forEach((jobKey, steps) -> {
                assertThat(steps).as("%s declares at least one step", jobKey).isNotEmpty();
                assertThat(steps.get(0).requirePrecedingExitCodeZero())
                        .as("%s's first step is not gated", jobKey).isFalse();
                assertThat(steps).extracting(StepContract::name).doesNotHaveDuplicates();
                assertThat(steps).allSatisfy(step -> {
                    assertThat(step.name()).isNotBlank();
                    assertThat(step.program()).isNotBlank();
                });
            });
            assertThat(JobContracts.REQUIRED_STEPS.get("statement-generation-job-a"))
                    .as("CREASTMT.JCL gates exactly three of its five steps")
                    .filteredOn(StepContract::requirePrecedingExitCodeZero)
                    .extracting(StepContract::name)
                    .containsExactly("STEP020", "STEP030", "STEP040");
            assertThat(JobContracts.REQUIRED_STEPS.values().stream()
                    .flatMap(List::stream)
                    .filter(StepContract::requirePrecedingExitCodeZero)
                    .count())
                    .as("CREASTMT.JCL carries the only COND=(0,NE) gating in the estate")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("only the interest calculator may declare a parameter, and only its own")
        void onlyTheParameterisedJobMayDeclareAParameter() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-balance-job", new JobContract("CBACT01C",
                            List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER,
                                    "string", "2022071800")),
                            List.of(new StepContract("STEP05", "CBACT01C", false)), null, Map.of()))
                            .validate(validCatalogue()))
                    .withMessageContaining("its JCL step carries no PARM");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-interest-calc-job", new JobContract(
                            "CBACT04C",
                            List.of(new JobParameterContract("reportDate", "string", "2022071800")),
                            List.of(new StepContract("STEP15", "CBACT04C", false)), null, Map.of()))
                            .validate(validCatalogue()))
                    .withMessageContaining("it takes exactly one: 'parmDate'");
        }

        @Test
        @DisplayName("the interest calculator's parameter value must be exactly the COBOL PIC X(10) "
                + "width")
        void theParameterValueMustMatchTheCobolFieldWidth() {
            for (String wrongWidth : new String[] { "202207180", "20220718000" }) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> jobsWith("account-interest-calc-job", interestCalcWith(
                                wrongWidth)).validate(validCatalogue()))
                        .withMessageContaining("must be exactly 10")
                        .withMessageContaining("PARM-DATE PIC X(10)");
            }
            assertThatNoException().isThrownBy(() -> jobsWith("account-interest-calc-job",
                    interestCalcWith("2022071800")).validate(validCatalogue()));
        }

        @Test
        @DisplayName("a dataset override is an alias or an inline dataset, never an ambiguous mixture")
        void aDatasetOverrideIsAnAliasOrAnInlineDataset() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithOverride("SORTIN", new JobDatasetBinding("TRANSACT",
                            LOCATION, "sequential", false, "FB", null, 350, "CVTRA05Y", null, null, null,
                            null)).validate(validCatalogue()))
                    .withMessageContaining("is ambiguous");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithOverride("SORTIN", new JobDatasetBinding(null, null,
                            null, false, null, null, null, null, null, null, null, null))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares neither an alias nor a dataset of its own");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithOverride("SORTIN", new JobDatasetBinding("NOSUCHDD",
                            null, null, false, null, null, null, null, null, null, null, null))
                            .validate(validCatalogue()))
                    .withMessageContaining("No dataset binding is configured for DD name 'NOSUCHDD'");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithOverride("SORTIN", inline(0))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares record-length 0");
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithOverride("SORTIN", inlineWithBlockSize(-1))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares block-size -1");
            assertThatNoException().isThrownBy(() -> jobsWithOverride("SORTIN", alias("TRANSACT"))
                    .validate(validCatalogue()));
            assertThatNoException().isThrownBy(() -> jobsWithOverride("SORTIN", inline(350))
                    .validate(validCatalogue()));
        }

        @Test
        @DisplayName("the global catalogue is required, because an alias means nothing without it")
        void theGlobalCatalogueIsRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> validJobs().validate(null))
                    .withMessageContaining("global dataset catalogue is required");
        }

        @Test
        @DisplayName("a step that names a program but no name, and one that names neither, are both "
                + "refused - each naming the property path that is empty")
        void aStepMissingEitherHalfIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-balance-job", new JobContract("CBACT01C",
                            List.of(), List.of(new StepContract("STEP05", " ", false)), null,
                            Map.of())).validate(validCatalogue()))
                    .withMessageContaining("its step at position 0 declares no value for "
                            + "carddemo.jobs.account-balance-job.steps[0].program")
                    .withMessageContaining("the program from its EXEC PGM=");

            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-balance-job", new JobContract("CBACT01C",
                            List.of(), List.of(new StepContract(null, null, false)), null,
                            Map.of())).validate(validCatalogue()))
                    .withMessageContaining("carddemo.jobs.account-balance-job.steps[0].name and "
                            + "carddemo.jobs.account-balance-job.steps[0].program");
        }

        @Test
        @DisplayName("resolving a contract whose step omits its name is refused descriptively, not "
                + "with a NullPointerException from the first lookup")
        void resolvingAContractWithAnIncompleteStepIsRefusedBeforeAnyLookup() {
            JobContracts contracts = jobsWith("account-balance-job", new JobContract("CBACT01C",
                    List.of(), List.of(new StepContract(null, "CBACT01C", false)), null, Map.of()));

            assertThatIllegalStateException().isThrownBy(() -> contracts.contract("account-balance-job"))
                    .as("the accessor every job class resolves its contract through")
                    .withMessageContaining("The carddemo.jobs contract for 'account-balance-job' is "
                            + "invalid")
                    .withMessageContaining("carddemo.jobs.account-balance-job.steps[0].name")
                    .withMessageContaining("the name from the step label");

            JobContract incomplete = new JobContract("CBACT01C", List.of(),
                    List.of(new StepContract(null, "CBACT01C", false)), null, Map.of());

            assertThatIllegalStateException().isThrownBy(() -> incomplete.step("STEP05"))
                    .withMessageContaining("declares no step named 'STEP05'");

            assertThatNoException()
                    .isThrownBy(() -> validJobs().contract("account-balance-job"));
        }

        @Test
        @DisplayName("an inline override that declares an organization but no width is refused, "
                + "because a width can never be inferred")
        void anInlineOverrideWithoutAWidthIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> jobsWithOverride("SORTIN", new JobDatasetBinding(null, null,
                            "sequential", false, "FB", null, null, null, null, null, null, null))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares "
                            + "neither an alias nor a record length");
        }

        @Test
        @DisplayName("an inline override may declare a block size of zero, which BLKSIZE=0 asks for")
        void anInlineOverrideMayDeclareASystemDeterminedBlockSize() {
            assertThatNoException().isThrownBy(() -> jobsWithOverride("SORTIN",
                    inlineWithBlockSize(0)).validate(validCatalogue()));
        }

        @Test
        @DisplayName("an inline override that declares a width but no location is refused, because it "
                + "has no global entry to inherit one from")
        void anInlineOverrideWithoutALocationIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithOverride("SORTIN", new JobDatasetBinding(null, null,
                            null, false, null, null, 350, null, null, null, null, null))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares no dsname");
        }

        @Test
        @DisplayName("an inline override whose dsname is a filesystem path is refused at startup, not "
                + "when a job first opens it")
        void anInlineOverrideWithAFilesystemPathIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWithOverride("SORTIN",
                            inlineAt("/tmp/carddemo-test/transact-bkup.txt"))
                            .validate(validCatalogue()))
                    .withMessageContaining("is not a z/OS dataset name")
                    .withMessageContaining("SORTIN");
        }

        @Test
        @DisplayName("an inline override may name a generation, because a GDG suffix is part of the "
                + "grammar")
        void anInlineOverrideMayNameAGeneration() {
            assertThatNoException().isThrownBy(() -> jobsWithOverride("SORTIN",
                    inlineAt(LOCATION + "(+1)")).validate(validCatalogue()));
        }

        @Test
        @DisplayName("a declared parameter of any type but string is refused, because the one PARM in "
                + "the estate is never parsed")
        void aParameterOfAnyOtherTypeIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> jobsWith("account-interest-calc-job", new JobContract(
                            "CBACT04C",
                            List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "long",
                                    "2022071800")),
                            List.of(new StepContract("STEP15", "CBACT04C", false)), null, Map.of()))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares type 'long'")
                    .withMessageContaining("never parsed");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> jobsWith("account-interest-calc-job", new JobContract(
                            "CBACT04C",
                            List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER,
                                    "string", "   ")),
                            List.of(new StepContract("STEP15", "CBACT04C", false)), null, Map.of()))
                            .validate(validCatalogue()))
                    .withMessageContaining("declares no value");
        }

        @Test
        @DisplayName("a contract is looked up by exact key, and an unknown one fails where it is "
                + "looked up")
        void aContractIsLookedUpByExactKey() {
            JobContracts contracts = validJobs();

            assertThat(contracts.contract("account-balance-job").program()).isEqualTo("CBACT01C");
            assertThatIllegalStateException()
                    .isThrownBy(() -> contracts.contract("account_balance_job"))
                    .withMessageContaining("No job contract is configured for 'account_balance_job'")
                    .withMessageContaining("matched exactly");
        }

        @Test
        @DisplayName("a job's own step and DD-name lookups resolve what it declares and fail on what "
                + "it does not")
        void aJobResolvesItsOwnStepsAndDdNames() {
            JobContract statement = jobsWithOverride("SORTIN", alias("TRANSACT"))
                    .contract("statement-generation-job-a");

            assertThat(statement.step("STEP040").program()).isEqualTo("CBSTM03A");
            assertThatIllegalStateException().isThrownBy(() -> statement.step("STEP999"))
                    .withMessageContaining("declares no step named 'STEP999'");
            assertThat(statement.datasetBinding("SORTIN", validCatalogue()).recordLength())
                    .as("an alias resolves through the global entry it names")
                    .isEqualTo(50);
            assertThat(statement.datasetBinding("ACCTDAT", validCatalogue()).copybook())
                    .as("a DD name with no override falls through to the global catalogue")
                    .isEqualTo("CVACT01Y");
            assertThat(statement.jobParameters().getParameters())
                    .as("a job with no declared parameter launches with none")
                    .isEmpty();
        }

        private JobContracts validJobs() {
            JobContracts contracts = new JobContracts();
            JobContracts.REQUIRED_JOBS.keySet().forEach(jobKey -> contracts.put(jobKey,
                    JobContracts.PARAMETERISED_JOB.equals(jobKey)
                            ? interestCalcWith("2022071800")
                            : job(jobKey)));
            return contracts;
        }

        private JobContracts jobsWith(String jobKey, JobContract contract) {
            JobContracts contracts = validJobs();
            contracts.put(jobKey, contract);
            return contracts;
        }

        private JobContracts jobsWithOverride(String ddName, JobDatasetBinding override) {
            Map<String, JobDatasetBinding> overrides = new LinkedHashMap<>();
            overrides.put(ddName, override);
            return jobsWith("statement-generation-job-a", new JobContract("CBSTM03A", List.of(),
                    JobContracts.REQUIRED_STEPS.get("statement-generation-job-a"), null, overrides));
        }

        private JobContracts jobsWithSteps(String jobKey, List<StepContract> steps) {
            return jobsWith(jobKey, new JobContract(JobContracts.REQUIRED_JOBS.get(jobKey),
                    List.of(), steps, null, Map.of()));
        }

        private static List<StepContract> withoutStepNamed(List<StepContract> steps, String name) {
            return steps.stream().filter(step -> !step.name().equals(name)).toList();
        }

        private static List<StepContract> withProgramOfStepNamed(List<StepContract> steps, String name,
                String program) {
            return steps.stream()
                    .map(step -> step.name().equals(name)
                            ? new StepContract(name, program, step.requirePrecedingExitCodeZero())
                            : step)
                    .toList();
        }

        private static List<StepContract> withGateOfStepNamed(List<StepContract> steps, String name,
                boolean gated) {
            return steps.stream()
                    .map(step -> step.name().equals(name)
                            ? new StepContract(name, step.program(), gated)
                            : step)
                    .toList();
        }

        private static JobContract job(String jobKey) {
            return new JobContract(JobContracts.REQUIRED_JOBS.get(jobKey), List.of(),
                    JobContracts.REQUIRED_STEPS.get(jobKey), null, Map.of());
        }

        private static JobContract inventedJob(String program) {
            return new JobContract(program, List.of(),
                    List.of(new StepContract("STEP05", program, false)), null, Map.of());
        }

        private static JobContract interestCalcWith(String parmDateValue) {
            return new JobContract("CBACT04C",
                    List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string",
                            parmDateValue)),
                    JobContracts.REQUIRED_STEPS.get(JobContracts.PARAMETERISED_JOB), null, Map.of());
        }

        private static JobDatasetBinding alias(String globalKey) {
            return new JobDatasetBinding(globalKey, null, null, false, null, null, null, null, null, null,
                    null, null);
        }

        private static JobDatasetBinding inline(int recordLength) {
            return new JobDatasetBinding(null, LOCATION, "sequential", false, "FB", null,
                    recordLength, "CVTRA05Y", null, null, null, null);
        }

        private static JobDatasetBinding inlineWithBlockSize(int blockSize) {
            return new JobDatasetBinding(null, LOCATION, "sequential", false, "FB", blockSize, 350,
                    "CVTRA05Y", null, null, null, null);
        }

        private static JobDatasetBinding inlineAt(String dsname) {
            return new JobDatasetBinding(null, dsname, "sequential", false, "FB", 0, 350,
                    "CVTRA05Y", null, null, null, null);
        }

        private DatasetBindings validCatalogue() {
            DatasetBindings bindings = new DatasetBindings();
            Map<String, String> bases = Map.of(
                    "CARDAIX", "CARDDAT", "CXACAIX", "CCXREF", "XREFFIL1", "CCXREF");
            DatasetBindings.REQUIRED_DD_NAMES.forEach(ddName -> {
                String base = bases.get(ddName);
                if (base != null) {
                    bindings.put(ddName, new DatasetBinding(LOCATION, "aix-path", false, "FB", null,
                            50, "CVACT02Y", 11, 0, base, "SENTINEL-ALT-KEY"));
                } else if (bases.containsValue(ddName)) {
                    bindings.put(ddName, new DatasetBinding(LOCATION, "ksds", false, "FB", null, 50,
                            "CVACT02Y", 11, 0, null, null));
                } else {
                    bindings.put(ddName, new DatasetBinding(LOCATION, "sequential", false, "FB", null,
                            50, "CVACT01Y", null, null, null, null));
                }
            });
            return bindings;
        }
    }

    @Nested
    @DisplayName("ParmDateJobParametersValidator - the PARM lands in a PIC X(10) field")
    class ParmDateValidation {
        private final JobParametersValidator validator =
                new BatchConfig.ParmDateJobParametersValidator();

        @Test
        @DisplayName("the JCL's own value is accepted")
        void theJclValueIsAccepted() throws JobParametersInvalidException {
            validator.validate(new JobParametersBuilder()
                    .addString(BatchConfig.PARM_DATE_PARAMETER, "2022071800")
                    .toJobParameters());
        }

        @Test
        @DisplayName("an absent parameter is refused by the framework's own required-key diagnostic")
        void anAbsentParameterIsRefused() {
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(new JobParameters()))
                    .withMessageContaining(BatchConfig.PARM_DATE_PARAMETER);
        }

        @Test
        @DisplayName("a blank parameter is refused, because it would write short identifiers instead "
                + "of failing")
        void aBlankParameterIsRefused() {
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(new JobParametersBuilder()
                            .addString(BatchConfig.PARM_DATE_PARAMETER, "          ")
                            .toJobParameters()))
                    .withMessageContaining("must not be blank");
        }

        @ParameterizedTest(name = "a {0}-character value is refused")
        @ValueSource(strings = { "2022", "202207180", "20220718000" })
        @DisplayName("a value of any other width is refused, naming the width and the COBOL field")
        void aValueOfAnyOtherWidthIsRefused(String wrongWidth) {
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(new JobParametersBuilder()
                            .addString(BatchConfig.PARM_DATE_PARAMETER, wrongWidth)
                            .toJobParameters()))
                    .withMessageContaining("must be exactly 10")
                    .withMessageContaining("PARM-DATE PIC X(10)")
                    .withMessageContaining("DELIMITED BY SIZE");
        }

        @Test
        @DisplayName("the width rule is scoped to parmDate, because a width belongs to a field rather "
                + "than to parameters in general")
        void theWidthRuleIsScopedToParmDate() {
            assertThat(new JobParameterContract("reportDate", "string", "2022-07-18")
                    .requireStringValue())
                    .isEqualTo("2022-07-18");
            assertThat(new JobParameterContract("reportDate", "STRING", "x").requireStringValue())
                    .as("the declared type is compared without regard to case")
                    .isEqualTo("x");
        }

        @Test
        @DisplayName("the declared width is 10, taken from the COBOL field and not from the JCL "
                + "literal")
        void theDeclaredWidthIsTen() {
            assertThat(BatchConfig.PARM_DATE_WIDTH).isEqualTo(10);
            assertThat("2022071800".length()).isEqualTo(BatchConfig.PARM_DATE_WIDTH);
        }
    }

    @Nested
    @DisplayName("the shipped documents - both catalogues bind and validate under both profiles")
    class TheShippedConfigurationDocuments {
        @ParameterizedTest(name = "the {0} profile binds 27 datasets and 9 jobs, and both validate")
        @ValueSource(strings = { "default", "test" })
        @DisplayName("both shipped profiles satisfy the strict catalogues")
        void bothShippedProfilesSatisfyTheStrictCatalogues(String profile) {
            runnerFor(profile).run(context -> {
                assertThat(context).hasNotFailed();
                DatasetBindings datasets = context.getBean(DatasetBindings.class);
                JobContracts jobs = context.getBean(JobContracts.class);

                assertThat(datasets.keySet())
                        .containsExactlyInAnyOrderElementsOf(DatasetBindings.REQUIRED_DD_NAMES);
                assertThat(jobs.keySet())
                        .containsExactlyInAnyOrderElementsOf(JobContracts.REQUIRED_JOBS.keySet());
                assertThatNoException().isThrownBy(() -> jobs.validate(datasets));
            });
        }

        @ParameterizedTest(name = "the {0} profile's job-submission port binds and validates")
        @ValueSource(strings = { "default", "test" })
        @DisplayName("both shipped profiles satisfy the job-submission byte contract")
        void bothShippedProfilesSatisfyTheJobSubmissionContract(String profile) {
            runnerFor(profile).run(context -> {
                assertThat(context).hasNotFailed();
                JobSubmissionProperties port = context.getBean(JobSubmissionProperties.class);

                assertThat(port.queueName()).isEqualTo("JOBS");
                assertThat(port.ddName()).isEqualTo("INREADER");
                assertThat(port.recordLength())
                        .isEqualTo(JobSubmissionProperties.TDQ_RECORD_LENGTH);
                assertThat(port.recordFormat())
                        .isEqualTo(JobSubmissionProperties.TDQ_RECORD_FORMAT);
                assertThat(port.blockFormat()).isEqualTo(JobSubmissionProperties.TDQ_BLOCK_FORMAT);
                assertThat(port.disposition()).isEqualTo(JobSubmissionProperties.TDQ_DISPOSITION);
                assertThatNoException().isThrownBy(port::validate);
            });
        }

        @Test
        @DisplayName("the shipped default profile provisions no job-submission location at all")
        void theDefaultProfileProvisionsNoLocation() {
            runnerFor("default").run(context -> {
                assertThat(context).hasNotFailed();
                JobSubmissionProperties port = context.getBean(JobSubmissionProperties.class);

                assertThat(port.approvedRoot()).isEmpty();
                assertThat(port.destination()).isEmpty();

                assertThatNoException().isThrownBy(port::validate);
                assertThat(new ReportRequestController.InternalReaderJobSubmissionPort(port)
                        .writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                        .isEqualTo(FileStatus.NOTOPEN);
            });
        }

        @Test
        @DisplayName("the test profile provisions its own root, and the destination is inside it")
        void theTestProfileProvisionsAnIsolatedRoot() {
            runnerFor("test").run(context -> {
                assertThat(context).hasNotFailed();
                JobSubmissionProperties port = context.getBean(JobSubmissionProperties.class);

                assertThatNoException().isThrownBy(port::validate);
                Path resolved = port.destinationPath();
                assertThat(resolved.isAbsolute()).isTrue();
                assertThat(resolved.startsWith(port.approvedRootPath())).isTrue();
                assertThat(resolved.endsWith(Paths.get("inreader", "JOBS"))).isTrue();
            });
        }

        @Test
        @DisplayName("the test profile gives each context its own in-memory database")
        void theTestProfileIsolatesTheDatabasePerContext() {
            List<String> urls = new ArrayList<>();
            for (int context = 0; context < 2; context++) {
                runnerFor("test")
                        .withUserConfiguration(BoundDataSourceProperties.class)
                        .run(loaded -> {
                            assertThat(loaded).hasNotFailed();
                            urls.add(loaded.getBean(DataSourceProperties.class).getUrl());
                        });
            }

            assertThat(urls).hasSize(2);
            assertThat(urls).allSatisfy(url -> assertThat(url)
                    .startsWith("jdbc:h2:mem:carddemo_test_")
                    .contains("DB_CLOSE_DELAY=-1")
                    .contains("DB_CLOSE_ON_EXIT=FALSE"));
            assertThat(urls.get(0)).isNotEqualTo(urls.get(1));
        }

        @Test
        @DisplayName("the test profile's owned output root carries both isolation discriminators")
        void theTestProfileIsolatesItsOutputRoot() {
            runnerFor("test").run(context -> {
                assertThat(context).hasNotFailed();
                String workDir = context.getEnvironment().getProperty("carddemo.test.work-dir");
                String cloneId = context.getEnvironment().getProperty("carddemo.test.clone-id");
                String runId = context.getEnvironment().getProperty("carddemo.test.run-id");

                assertThat(workDir).contains("clone-" + cloneId).contains("run-" + runId);
                assertThat(context.getEnvironment().getProperty("carddemo.test.work-dir"))
                        .isEqualTo(workDir);
                assertThat(runId).isNotBlank();
                assertThat(cloneId).isNotBlank();

                JobSubmissionProperties port = context.getBean(JobSubmissionProperties.class);
                assertThat(port.destination()).startsWith(workDir);
                assertThat(port.approvedRoot()).isEqualTo(workDir);
            });
        }

        @Test
        @DisplayName("the default arm reads the default document under an externally activated "
                + "profile, by system property or by environment variable")
        void theDefaultArmReadsTheDefaultDocumentUnderAnExternallyActivatedProfile() {
            List<String> observed = new ArrayList<>();
            runnerFor(DEFAULT_PROFILE).run(context -> observed.add(
                    context.getBean(DatasetBindings.class).binding("ACCTDAT").dsname()));
            runnerFor(DEFAULT_PROFILE)
                    .withSystemProperties("spring.profiles.active=test")
                    .run(context -> observed.add(
                            context.getBean(DatasetBindings.class).binding("ACCTDAT").dsname()));
            runnerFor(DEFAULT_PROFILE, processEnvironmentActivating("test"))
                    .run(context -> observed.add(
                            context.getBean(DatasetBindings.class).binding("ACCTDAT").dsname()));

            assertThat(observed).hasSize(3);
            assertThat(observed)
                    .as("one document, one location, whatever the process was told")
                    .containsOnly(observed.get(0));
            assertThat(observed.get(0))
                    .as("the shipped default location, not the fixture profile's")
                    .doesNotStartWith("CARDDEMO.TEST.");
            runnerFor("test").run(context -> assertThat(
                    context.getBean(DatasetBindings.class).binding("ACCTDAT").dsname())
                    .startsWith("CARDDEMO.TEST."));
        }

        private ApplicationContextRunner runnerFor(String profile) {
            return runnerFor(profile, context -> { });
        }

        private ApplicationContextRunner runnerFor(String profile,
                ApplicationContextInitializer<ConfigurableApplicationContext> inheritedEnvironment) {
            return new ApplicationContextRunner()
                    .withInitializer(inheritedEnvironment)
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(context -> RandomValuePropertySource
                            .addToEnvironment(context.getEnvironment()))
                    .withConfiguration(AutoConfigurations.of(
                            PropertyPlaceholderAutoConfiguration.class))
                    .withUserConfiguration(ShippedCatalogues.class)
                    .withPropertyValues(ACTIVE_PROFILE_PROPERTY
                            + (DEFAULT_PROFILE.equals(profile) ? NO_ACTIVE_PROFILE : profile));
        }

        @Configuration
        @EnableConfigurationProperties(DataSourceProperties.class)
        static class BoundDataSourceProperties {
        }

        @Configuration
        @EnableConfigurationProperties({
            DatasetBindings.class, JobContracts.class, JobSubmissionProperties.class })
        static class ShippedCatalogues {
        }
    }

    @Nested
    @DisplayName("DatasetBindings.binding - an unknown DD name fails where it is looked up")
    class DatasetBindingLookup {
        private static final DatasetBinding ACCTDAT = new DatasetBinding(
                "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS", "KSDS", false, "F", null, 300,
                "CVACT01Y", 11, null, null, null);

        @Test
        @DisplayName("a configured DD name returns its binding")
        void configuredKeyResolves() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", ACCTDAT);

            assertThat(bindings.binding("ACCTDAT")).isSameAs(ACCTDAT);
            assertThat(bindings.binding("ACCTDAT").recordLength()).isEqualTo(300);
            assertThat(bindings.binding("ACCTDAT").copybook()).isEqualTo("CVACT01Y");
        }

        @Test
        @DisplayName("an unknown DD name fails, listing the keys that are configured")
        void unknownKeyFails() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", ACCTDAT);

            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding("NOSUCHDD"))
                    .withMessageContaining("NOSUCHDD")
                    .withMessageContaining("never hard-coded in Java")
                    .withMessageContaining("ACCTDAT");
        }

        @Test
        @DisplayName("keys are matched exactly - there is no case-insensitive fallback")
        void keysAreCaseSensitive() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", ACCTDAT);

            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding("acctdat"))
                    .withMessageContaining("matched exactly");
        }

        @Test
        @DisplayName("an empty binding map still fails informatively rather than returning null")
        void emptyMapFails() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new DatasetBindings().binding("ACCTDAT"))
                    .withMessageContaining("ACCTDAT");
        }

        @Test
        @DisplayName("the binding record carries the copybook contract, alternate keys included")
        void bindingCarriesTheContract() {
            DatasetBinding path = new DatasetBinding(
                    "AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH", "PATH", false, "F", 1500, 150,
                    "CVACT02Y", 11, null, "CARDDAT", "CARD-ACCT-ID");

            assertThat(path.base()).isEqualTo("CARDDAT");
            assertThat(path.alternateKey()).isEqualTo("CARD-ACCT-ID");
            assertThat(path.gdg()).isFalse();
            assertThat(path.blockSize()).isEqualTo(1500);
            assertThat(path.organization()).isEqualTo("PATH");
            assertThat(path.recordFormat()).isEqualTo("F");
            assertThat(path.keyLength()).isEqualTo(11);
            assertThat(path.dsname()).endsWith("AIX.PATH");
        }
    }

    @Nested
    @DisplayName("CobolErrorHandler - publishes fixed text, and preserves a status it did not choose")
    class SanitizedErrorResponses {
        private static final String LEAK = "SENTINEL-INTERNAL-DETAIL";

        private final CobolErrorHandler handler = new CobolErrorHandler();

        @Test
        @DisplayName("an unreadable request body is answered 400 with fixed text and no leak")
        void anUnreadableBodyIsSanitized() {
            HttpMessageNotReadableException unreadable =
                    new HttpMessageNotReadableException(LEAK, emptyRequest());

            ResponseEntity<CobolErrorResponse> response =
                    CobolErrorHandler.handleUnreadableBody(unreadable);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code())
                    .isEqualTo(CobolErrorHandler.MALFORMED_REQUEST_CODE);
            assertThat(response.getBody().error()).isEqualTo("Bad Request");
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.UNREADABLE_BODY_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("a type mismatch is answered 400 with fixed text and no leak")
        void aTypeMismatchIsSanitized() {
            TypeMismatchException mismatch = new TypeMismatchException(LEAK, Integer.class);

            ResponseEntity<CobolErrorResponse> response = handler.handleTypeMismatch(mismatch);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.TYPE_MISMATCH_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("a data-access failure is answered 500 with fixed text and no leak")
        void aDataAccessFailureIsSanitized() {
            DataAccessResourceFailureException failure =
                    new DataAccessResourceFailureException(LEAK);

            ResponseEntity<CobolErrorResponse> response = handler.handleDataAccessFailure(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(CobolErrorHandler.DATASET_ACCESS_CODE);
            assertThat(response.getBody().error()).isEqualTo("Internal Server Error");
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.DATASET_ACCESS_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("an unclaimed failure carrying no status becomes 500 with fixed text")
        void anUnclaimedFailureBecomes500() {
            ResponseEntity<CobolErrorResponse> response =
                    handler.handleUnexpectedFailure(new IllegalArgumentException(LEAK));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("a configuration failure surfacing at request time discloses no configuration")
        void aConfigurationFailureIsSanitized() {
            IllegalStateException configurationFault = new IllegalStateException(
                    "No dataset binding is configured for DD name '" + LEAK + "'");

            ResponseEntity<CobolErrorResponse> response =
                    handler.handleUnexpectedFailure(configurationFault);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("the catch-all preserves a status the failure already carries - 404 stays 404")
        void theCatchAllPreservesACarriedStatus() {
            ResponseStatusException notFound =
                    new ResponseStatusException(HttpStatus.NOT_FOUND, LEAK);

            ResponseEntity<CobolErrorResponse> response = handler.handleUnexpectedFailure(notFound);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code())
                    .isEqualTo(CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE);
            assertThat(response.getBody().error()).isEqualTo("Not Found");
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("statusForFailure reads a carried status, and defaults to 500 without one")
        void statusForFailureReadsBothSides() {
            assertThat(CobolErrorHandler.statusForFailure(
                    new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED)))
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(CobolErrorHandler.statusForFailure(new IllegalStateException("plain")))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("a non-standard status carries the number but no invented reason phrase")
        void aNonStandardStatusInventsNoReasonPhrase() {
            HttpStatusCode unregistered = HttpStatusCode.valueOf(599);

            assertThat(CobolErrorHandler.reasonPhraseOf(unregistered)).isEmpty();
            CobolErrorResponse body = CobolErrorHandler.sanitizedBody(
                    CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE,
                    unregistered,
                    CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE);
            assertThat(body.code()).isEqualTo(CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE);
            assertThat(body.error()).isEmpty();
            assertThat(body.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("a registered status carries its standard reason phrase")
        void aRegisteredStatusCarriesItsReasonPhrase() {
            assertThat(CobolErrorHandler.reasonPhraseOf(HttpStatus.UNSUPPORTED_MEDIA_TYPE))
                    .isEqualTo("Unsupported Media Type");
        }

        @Test
        @DisplayName("no fixed message names a technology, a class, a path or a statement")
        void noFixedMessageNamesAnythingInternal() {
            List<String> fixed = List.of(
                    CobolErrorHandler.UNREADABLE_BODY_MESSAGE,
                    CobolErrorHandler.TYPE_MISMATCH_MESSAGE,
                    CobolErrorHandler.DATASET_ACCESS_MESSAGE,
                    CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE);

            assertThat(fixed).doesNotHaveDuplicates();
            assertThat(fixed).allSatisfy(message -> assertThat(message.toLowerCase())
                    .doesNotContain("exception")
                    .doesNotContain("sql")
                    .doesNotContain("select ")
                    .doesNotContain("jdbc")
                    .doesNotContain("jackson")
                    .doesNotContain("org.springframework")
                    .doesNotContain("com.vsergeychik")
                    .doesNotContain("/"));
        }
    }

    @Nested
    @DisplayName("JobSubmissionProperties.validate - the CSD byte contract and a safe destination")
    class JobSubmissionContract {
        private static final String ROOT = "/var/carddemo";
        private static final String TARGET = "/var/carddemo/inreader/JOBS";

        private JobSubmissionProperties paths(String root, String target) {
            return new JobSubmissionProperties("JOBS", "INREADER", "US-ASCII",
                    JobSubmissionProperties.TDQ_RECORD_LENGTH,
                    JobSubmissionProperties.TDQ_RECORD_FORMAT,
                    JobSubmissionProperties.TDQ_BLOCK_FORMAT,
                    JobSubmissionProperties.TDQ_DISPOSITION, root, target);
        }

        private JobSubmissionProperties bytes(int length, String format, String block, String mode) {
            return new JobSubmissionProperties("JOBS", "INREADER", "US-ASCII", length, format,
                    block, mode, ROOT, TARGET);
        }

        private JobSubmissionProperties identity(String queueName, String ddName) {
            return new JobSubmissionProperties(queueName, ddName, "US-ASCII", 80, "FIXED",
                    "UNBLOCKED", "MOD", ROOT, TARGET);
        }

        private JobSubmissionProperties charset(String charset) {
            return new JobSubmissionProperties("JOBS", "INREADER", charset, 80, "FIXED",
                    "UNBLOCKED", "MOD", ROOT, TARGET);
        }

        @ParameterizedTest(name = "queue-name [{0}] is refused")
        @ValueSource(strings = { "JOBSX", "JOB", "SUBMIT", "INREADER" })
        @DisplayName("a queue name that is not the CSD's TDQUEUE(JOBS) refuses startup")
        void aForeignQueueNameRefusesStartup(String foreign) {
            assertThatIllegalStateException().isThrownBy(identity(foreign, "INREADER")::validate)
                    .withMessageContaining("queue-name")
                    .withMessageContaining("JOBS");
        }

        @ParameterizedTest(name = "dd-name [{0}] is refused")
        @ValueSource(strings = { "INREADR", "READER", "JOBS", "SYSIN" })
        @DisplayName("a DD name that is not the CSD's DDNAME(INREADER) refuses startup")
        void aForeignDdNameRefusesStartup(String foreign) {
            assertThatIllegalStateException().isThrownBy(identity("JOBS", foreign)::validate)
                    .withMessageContaining("dd-name")
                    .withMessageContaining("INREADER");
        }

        @ParameterizedTest(name = "[{0}] and [{1}] are accepted")
        @CsvSource({ "JOBS,INREADER", "jobs,inreader", "Jobs,InReader", " JOBS , INREADER " })
        @DisplayName("the CSD identifiers are matched without regard to case or surrounding space")
        void theCsdIdentifiersAreMatchedCaseInsensitively(String queueName, String ddName) {
            assertThatNoException().isThrownBy(identity(queueName, ddName)::validate);
        }

        @ParameterizedTest(name = "a blank charset [{0}] is refused")
        @ValueSource(strings = { "", "   " })
        @DisplayName("the code page must be configured, because it cannot be inferred")
        void theCodePageMustBePresent(String blank) {
            assertThatIllegalStateException().isThrownBy(charset(blank)::validate)
                    .withMessageContaining("charset")
                    .withMessageContaining("IBM037")
                    .withMessageContaining("US-ASCII");
        }

        @Test
        @DisplayName("an absent charset is refused rather than defaulted")
        void anAbsentCodePageIsRefused() {
            assertThatIllegalStateException().isThrownBy(charset(null)::validate)
                    .withMessageContaining("charset");
        }

        @ParameterizedTest(name = "an unknown charset [{0}] is refused")
        @ValueSource(strings = { "IBM037X", "not a charset", "EBCDIC" })
        @DisplayName("a code page this JVM does not have is refused at startup, not at the first write")
        void anUnknownCodePageIsRefused(String unknown) {
            assertThatIllegalStateException().isThrownBy(charset(unknown)::validate)
                    .withMessageContaining("charset")
                    .withMessageContaining("the JVM supports");
        }

        @ParameterizedTest(name = "a multi-byte charset [{0}] is refused")
        @ValueSource(strings = { "UTF-8", "UTF-16", "IBM930" })
        @DisplayName("a variable-width code page is refused, because an 80-byte record needs one byte "
                + "per character")
        void aMultiByteCodePageIsRefused(String multiByte) {
            assertThatIllegalStateException().isThrownBy(charset(multiByte)::validate)
                    .withMessageContaining("charset");
        }

        @ParameterizedTest(name = "[{0}] is accepted and resolves")
        @ValueSource(strings = { "IBM037", "US-ASCII", "ISO-8859-1", " ibm037 " })
        @DisplayName("a single-byte code page is accepted and published for the writer to encode with")
        void aSingleByteCodePageIsAcceptedAndPublished(String single) {
            JobSubmissionProperties port = charset(single);

            assertThatNoException().isThrownBy(port::validate);
            assertThat(port.queueCharset()).isEqualTo(Charset.forName(single.trim()));
        }

        @ParameterizedTest(name = "reading the code page directly refuses [{0}] by name too")
        @ValueSource(strings = { "IBM037X", "not a charset", "EBCDIC", "IBM-NOSUCH-9999" })
        @DisplayName("the accessor refuses an unknown code page with the same key-naming diagnostic the "
                + "validator gives, because the port reads it in its own constructor")
        void theAccessorRefusesAnUnknownCodePageByName(String unknown) {
            assertThatIllegalStateException().isThrownBy(charset(unknown)::queueCharset)
                    .withMessageContaining("carddemo.job-submission.charset")
                    .withMessageContaining("the JVM supports")
                    .withMessageContaining("IBM037")
                    .havingCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "reading the code page directly refuses a blank value [{0}]")
        @ValueSource(strings = { "", "   " })
        @DisplayName("a blank code page read through the accessor names the key rather than raising an "
                + "IllegalCharsetNameException with no message")
        void theAccessorRefusesABlankCodePageByName(String blank) {
            assertThatIllegalStateException().isThrownBy(charset(blank)::queueCharset)
                    .withMessageContaining("carddemo.job-submission.charset")
                    .withMessageContaining("it declares no value")
                    .withMessageContaining("US-ASCII");
        }

        @Test
        @DisplayName("an absent code page read through the accessor is refused, not defaulted")
        void theAccessorRefusesAnAbsentCodePage() {
            assertThatIllegalStateException().isThrownBy(charset(null)::queueCharset)
                    .withMessageContaining("carddemo.job-submission.charset");
        }

        @ParameterizedTest(name = "reading the code page directly refuses the multi-byte [{0}]")
        @ValueSource(strings = { "UTF-8", "UTF-16", "IBM930" })
        @DisplayName("a variable-width code page is refused by the accessor as well, so an 80-character "
                + "record can never be encoded into some other number of bytes")
        void theAccessorRefusesAMultiByteCodePage(String multiByte) {
            assertThatIllegalStateException().isThrownBy(charset(multiByte)::queueCharset)
                    .withMessageContaining("carddemo.job-submission.charset")
                    .withMessageContaining("RECORDSIZE("
                            + JobSubmissionProperties.TDQ_RECORD_LENGTH + ")");
        }

        @Test
        @DisplayName("the approved root is published as a normalised path for the writer to bound "
                + "itself with")
        void theApprovedRootIsPublished() {
            JobSubmissionProperties port = paths(ROOT, TARGET);

            assertThat(port.approvedRootPath()).isEqualTo(Paths.get(ROOT));
            assertThat(port.destinationPath().startsWith(port.approvedRootPath())).isTrue();
        }

        @Test
        @DisplayName("a contract matching the CSD with a contained destination validates")
        void aValidContractValidates() {
            JobSubmissionProperties port = paths(ROOT, TARGET);

            assertThatNoException().isThrownBy(port::validate);
            assertThat(port.destinationPath()).isEqualTo(Paths.get(TARGET));
        }

        @ParameterizedTest(name = "a blank queue-name [{0}] is refused")
        @ValueSource(strings = { "", "   " })
        @DisplayName("the queue name must be present")
        void theQueueNameMustBePresent(String blank) {
            JobSubmissionProperties port = new JobSubmissionProperties(blank, "INREADER",
                    "US-ASCII", 80, "FIXED", "UNBLOCKED", "MOD", ROOT, TARGET);

            assertThatIllegalStateException().isThrownBy(port::validate)
                    .withMessageContaining("queue-name")
                    .withMessageContaining("TDQUEUE(JOBS)");
        }

        @Test
        @DisplayName("an absent DD name is refused, naming the CSD DDNAME it should carry")
        void theDdNameMustBePresent() {
            JobSubmissionProperties port = new JobSubmissionProperties("JOBS", null,
                    "US-ASCII", 80, "FIXED", "UNBLOCKED", "MOD", ROOT, TARGET);

            assertThatIllegalStateException().isThrownBy(port::validate)
                    .withMessageContaining("dd-name")
                    .withMessageContaining("DDNAME(INREADER)");
        }

        @ParameterizedTest(name = "record-length {0} is refused - RECORDSIZE(80) is the contract")
        @ValueSource(ints = { 0, 79, 81, 133 })
        @DisplayName("only the CSD's RECORDSIZE(80) is accepted")
        void onlyEightyByteRecordsAreAccepted(int wrongLength) {
            assertThatIllegalStateException()
                    .isThrownBy(bytes(wrongLength, "FIXED", "UNBLOCKED", "MOD")::validate)
                    .withMessageContaining("record-length")
                    .withMessageContaining("RECORDSIZE(80)")
                    .withMessageContaining("gate G42");
        }

        @Test
        @DisplayName("a record format other than FIXED is refused")
        void onlyFixedRecordsAreAccepted() {
            assertThatIllegalStateException()
                    .isThrownBy(bytes(80, "V", "UNBLOCKED", "MOD")::validate)
                    .withMessageContaining("record-format")
                    .withMessageContaining("RECORDFORMAT(FIXED)");
        }

        @Test
        @DisplayName("a block format other than UNBLOCKED is refused")
        void onlyUnblockedRecordsAreAccepted() {
            assertThatIllegalStateException()
                    .isThrownBy(bytes(80, "FIXED", "BLOCKED", "MOD")::validate)
                    .withMessageContaining("block-format")
                    .withMessageContaining("BLOCKFORMAT(UNBLOCKED)");
        }

        @Test
        @DisplayName("a disposition other than MOD is refused - anything else discards records")
        void onlyModIsAccepted() {
            assertThatIllegalStateException()
                    .isThrownBy(bytes(80, "FIXED", "UNBLOCKED", "NEW")::validate)
                    .withMessageContaining("disposition")
                    .withMessageContaining("DISPOSITION(MOD)");
        }

        @Test
        @DisplayName("the three textual CSD values are compared ignoring case, the length exactly")
        void theTextualValuesAreCaseInsensitive() {
            assertThatNoException()
                    .isThrownBy(bytes(80, "fixed", "unblocked", "mod")::validate);
        }

        @ParameterizedTest(name = "an absent {0} is refused rather than defaulted")
        @ValueSource(strings = { "approved-root", "destination" })
        @DisplayName("neither path is defaulted inside Java")
        void neitherPathIsDefaulted(String key) {
            JobSubmissionProperties port = "approved-root".equals(key)
                    ? paths("  ", TARGET)
                    : paths(ROOT, null);

            assertThatIllegalStateException().isThrownBy(port::validate)
                    .withMessageContaining(key)
                    .withMessageContaining("declares no path");
        }

        @Test
        @DisplayName("a syntactically invalid path is reported as one, with the cause retained")
        void aMalformedPathIsReportedAsMalformed() {
            assertThatIllegalStateException()
                    .isThrownBy(paths(ROOT, "/var/carddemo/\u0000JOBS")::validate)
                    .withMessageContaining("destination")
                    .withMessageContaining("not a valid path")
                    .withCauseInstanceOf(java.nio.file.InvalidPathException.class);
        }

        @Test
        @DisplayName("an upward traversal segment is refused before the path is normalized")
        void traversalIsRefusedBeforeNormalization() {
            String traversing = "/var/carddemo/inreader/../../../etc/passwd";

            assertThatIllegalStateException()
                    .isThrownBy(paths(ROOT, traversing)::validate)
                    .withMessageContaining("destination")
                    .withMessageContaining("'..' segment")
                    .withMessageContaining("before the path is normalized");
        }

        @Test
        @DisplayName("a traversal that would land back inside the root is still refused")
        void traversalInsideTheRootIsStillRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(paths(ROOT, "/var/carddemo/spool/../inreader/JOBS")::validate)
                    .withMessageContaining("'..' segment");
        }

        @Test
        @DisplayName("a directory whose name merely contains two dots is not traversal")
        void twoDotsInsideANameIsNotTraversal() {
            assertThatNoException()
                    .isThrownBy(paths(ROOT, "/var/carddemo/my..dir/JOBS")::validate);
        }

        @Test
        @DisplayName("a relative path is refused - it would resolve against an uncontrolled cwd")
        void aRelativePathIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(paths(ROOT, "target/inreader/JOBS")::validate)
                    .withMessageContaining("destination")
                    .withMessageContaining("is relative");
        }

        @ParameterizedTest(name = "a destination inside {0} is refused")
        @ValueSource(strings = { "app/cbl", "app/cpy", "app/cpy-bms", "app/bms", "app/jcl",
            "app/proc", "app/csd", "app/ctl", "app/catlg", "app/data" })
        @DisplayName("every read-only reference tree is refused as a destination")
        void everyReferenceTreeIsRefused(String tree) {
            assertThatIllegalStateException()
                    .isThrownBy(paths("/repo", "/repo/" + tree + "/JOBS")::validate)
                    .withMessageContaining("destination")
                    .withMessageContaining("read-only reference tree")
                    .withMessageContaining("practice B3");
        }

        @Test
        @DisplayName("a reference tree is refused as the approved root too, not only as a target")
        void aReferenceTreeIsRefusedAsTheRoot() {
            assertThatIllegalStateException()
                    .isThrownBy(paths("/repo/app/data", "/repo/app/data/JOBS")::validate)
                    .withMessageContaining("approved-root")
                    .withMessageContaining("read-only reference tree");
        }

        @Test
        @DisplayName("the set of rejected trees is exactly the ten the migration reads")
        void theRejectedTreesAreExactlyTheTen() {
            assertThat(JobSubmissionProperties.REFERENCE_TREES).containsExactlyInAnyOrder(
                    "app/cbl", "app/cpy", "app/cpy-bms", "app/bms", "app/jcl",
                    "app/proc", "app/csd", "app/ctl", "app/catlg", "app/data");
        }

        @Test
        @DisplayName("a destination equal to the approved root is refused")
        void theDestinationCannotBeTheRootItself() {
            assertThatIllegalStateException()
                    .isThrownBy(paths(ROOT, ROOT)::validate)
                    .withMessageContaining("the approved root itself")
                    .withMessageContaining(ROOT);
        }

        @Test
        @DisplayName("a destination outside the approved root is refused")
        void theDestinationMustBeInsideTheRoot() {
            assertThatIllegalStateException()
                    .isThrownBy(paths(ROOT, "/var/elsewhere/JOBS")::validate)
                    .withMessageContaining("outside the approved root")
                    .withMessageContaining("deliberate change of approved-root");
        }

        @Test
        @DisplayName("a sibling whose name merely begins with the root's name is outside it")
        void aNamePrefixedSiblingIsOutsideTheRoot() {
            assertThatIllegalStateException()
                    .isThrownBy(paths(ROOT, "/var/carddemo-backup/inreader/JOBS")::validate)
                    .withMessageContaining("outside the approved root");
        }

        @Test
        @DisplayName("an unknown property under carddemo.job-submission refuses startup")
        void anUnknownPropertyRefusesStartup() {
            new ApplicationContextRunner()
                    .withUserConfiguration(PortOnly.class)
                    .withPropertyValues(
                            "carddemo.job-submission.queue-name=JOBS",
                            "carddemo.job-submission.dd-name=INREADER",
                            "carddemo.job-submission.charset=US-ASCII",
                            "carddemo.job-submission.record-length=80",
                            "carddemo.job-submission.record-format=FIXED",
                            "carddemo.job-submission.block-format=UNBLOCKED",
                            "carddemo.job-submission.disposition=MOD",
                            "carddemo.job-submission.approved-root=" + ROOT,
                            "carddemo.job-submission.destination=" + TARGET,
                            "carddemo.job-submission.record-size=80")
                    .run(context -> assertThat(context).getFailure()
                            .hasMessageContaining("ignoreUnknownFields=false")
                            .hasStackTraceContaining("record-size"));
        }

        @Test
        @DisplayName("an unsafe destination refuses startup, not just a direct validate() call")
        void anUnsafeDestinationRefusesStartup() {
            new ApplicationContextRunner()
                    .withUserConfiguration(PortOnly.class)
                    .withPropertyValues(
                            "carddemo.job-submission.queue-name=JOBS",
                            "carddemo.job-submission.dd-name=INREADER",
                            "carddemo.job-submission.charset=US-ASCII",
                            "carddemo.job-submission.record-length=80",
                            "carddemo.job-submission.record-format=FIXED",
                            "carddemo.job-submission.block-format=UNBLOCKED",
                            "carddemo.job-submission.disposition=MOD",
                            "carddemo.job-submission.approved-root=/repo",
                            "carddemo.job-submission.destination=/repo/app/cbl/JOBS")
                    .run(context -> assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("read-only reference tree"));
        }

        @Configuration
        @EnableConfigurationProperties(JobSubmissionProperties.class)
        static class PortOnly {
            @Bean
            WebConfig.JobSubmissionValidator jobSubmissionValidator(
                    JobSubmissionProperties properties) {
                return new WebConfig().jobSubmissionValidator(properties);
            }
        }

        @Test
        @DisplayName("destinationPath normalizes, so the writer opens the path that was approved")
        void theDestinationPathIsNormalized() {
            Path resolved = paths(ROOT, "/var/carddemo/./inreader/JOBS").destinationPath();

            assertThat(resolved.isAbsolute()).isTrue();
            assertThat(resolved).isEqualTo(Paths.get(TARGET));
        }

        @Test
        @DisplayName("approvedRootPath normalizes the same way, so the two paths compare as names")
        void theApprovedRootPathIsNormalized() {
            Path resolved = paths("/var/carddemo/./", TARGET).approvedRootPath();

            assertThat(resolved.isAbsolute()).isTrue();
            assertThat(resolved).isEqualTo(Paths.get(ROOT));
        }

        @Test
        @DisplayName("the approved root is a prefix of the destination once both are normalized")
        void theTwoPathsAreComparableAsNames() {
            JobSubmissionProperties port = paths(ROOT, TARGET);

            assertThat(port.destinationPath().startsWith(port.approvedRootPath())).isTrue();
            assertThat(port.approvedRootPath().relativize(port.destinationPath()))
                    .isEqualTo(Paths.get("inreader", "JOBS"));
        }
    }

    @Nested
    @DisplayName("the config package as a whole - both profiles behave as designed")
    class TheConfigurationPackageWiresUp {
        private ApplicationContextRunner runnerFor(String profile) {
            return runnerFor(profile, context -> { });
        }

        private ApplicationContextRunner runnerFor(String profile,
                ApplicationContextInitializer<ConfigurableApplicationContext> inheritedEnvironment) {
            return new ApplicationContextRunner()
                    .withInitializer(inheritedEnvironment)
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(context -> RandomValuePropertySource
                            .addToEnvironment(context.getEnvironment()))
                    .withConfiguration(AutoConfigurations.of(
                            PropertyPlaceholderAutoConfiguration.class))
                    .withUserConfiguration(CobolCharsetConfig.class, DataSourceConfig.class,
                            BatchConfig.class, WebConfig.class)
                    .withPropertyValues(ACTIVE_PROFILE_PROPERTY
                            + (DEFAULT_PROFILE.equals(profile) ? NO_ACTIVE_PROFILE : profile));
        }

        @Test
        @DisplayName("the default profile refuses to start without a deployment-supplied JDBC URL")
        void theDefaultProfileRefusesToStartWithoutAUrl() {
            runnerFor(DEFAULT_PROFILE).run(context -> assertThat(context).getFailure()
                    .rootCause()
                    .hasMessageContaining("spring.datasource.url"));
        }

        @Test
        @DisplayName("the default arm still refuses to start under an externally activated profile, "
                + "by system property or by environment variable")
        void theDefaultArmStillRefusesToStartUnderAnExternallyActivatedProfile() {
            List<ApplicationContextRunner> underAnInheritedProfile = List.of(
                    runnerFor(DEFAULT_PROFILE).withSystemProperties("spring.profiles.active=test"),
                    runnerFor(DEFAULT_PROFILE, processEnvironmentActivating("test")));

            for (ApplicationContextRunner runner : underAnInheritedProfile) {
                runner.run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("spring.datasource.url"));
            }
        }

        @Test
        @DisplayName("the test profile wires all four config classes on a database of its own")
        void theTestProfileWiresEveryConfigClass() {
            runnerFor("test").run(context -> {
                assertThat(context).hasNotFailed();

                assertThat(context).hasBean("carddemoEbcdicCharset");
                assertThat(context).hasBean("carddemoAsciiCharset");
                assertThat(context.getBean("carddemoDatasetCharset", Charset.class))
                        .isEqualTo(Charset.forName("US-ASCII"));

                HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                assertThat(pool.getJdbcUrl()).startsWith("jdbc:h2:mem:carddemo_test_");
                assertThat(pool.getDriverClassName()).isEqualTo("org.h2.Driver");
                assertThat(context.getBean(DatasetBindings.class))
                        .hasSize(DatasetBindings.REQUIRED_DD_NAMES.size());

                assertThat(context.getBean(JobContracts.class))
                        .hasSize(JobContracts.REQUIRED_JOBS.size());
                assertThat(context).hasSingleBean(PlatformTransactionManager.class);
                assertThat(context).hasBean("jobContractValidator");

                assertThat(context).hasSingleBean(Clock.class);
                assertThat(context).hasSingleBean(Jackson2ObjectMapperBuilderCustomizer.class);
                assertThat(context).hasSingleBean(CobolErrorHandler.class);
                assertThat(context).hasBean("jobSubmissionValidator");
                assertThat(context.getBean(JobSubmissionProperties.class).recordLength())
                        .isEqualTo(JobSubmissionProperties.TDQ_RECORD_LENGTH);
            });
        }
    }

    private static HttpInputMessage emptyRequest() {
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return InputStream.nullInputStream();
            }

            @Override
            public HttpHeaders getHeaders() {
                return HttpHeaders.EMPTY;
            }
        };
    }
}
