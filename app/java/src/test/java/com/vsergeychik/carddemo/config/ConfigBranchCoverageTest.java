package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobDatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler.FailureResponse;
import com.vsergeychik.carddemo.config.WebConfig.JobSubmissionProperties;

import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Decision-level tests for the configuration classes {@link CobolCharsetConfig},
 * {@link DataSourceConfig}, {@link BatchConfig} and {@link WebConfig}.
 *
 * <h2>Why these decisions in particular</h2>
 * The configuration package contains very little computation - it exists to bind values - but what it
 * does contain is all of the same kind: <em>a guard that refuses to let the application start on a
 * silently wrong default.</em> Each protects a constraint the migration depends on, and each shares
 * one property that makes a guard worth having here at all: the thing it prevents would not have
 * thrown. A wrong code page, a substituted driver or a wrong record width all produce running
 * software and wrong bytes, so startup is the only place the mistake is still cheap to see:
 *
 * <ul>
 *   <li>{@link CobolCharsetConfig#resolve(String, String)} refuses a blank, syntactically illegal or
 *       unsupported charset name rather than substituting the platform default, and every refusal
 *       names the property key that supplied the value. Decoding a fixed-width mainframe record in the wrong
 *       code page corrupts every byte of it without any error, so a loud failure at startup is the
 *       only safe behaviour. {@code IBM037} is the case that matters: it comes from the JDK's
 *       {@code jdk.charsets} module, which a trimmed runtime image omits.</li>
 *   <li>{@link DataSourceConfig#dataSource(DataSourceProperties)} refuses to build a
 *       {@link DataSource} with no URL, and refuses to publish one whose driver class cannot be
 *       determined or loaded - in particular it never substitutes the embedded database that sits on
 *       this classpath at test scope. No JDBC driver coordinate is pinned in this module by design -
 *       the datasets are VSAM and sequential files and there is no {@code EXEC SQL} anywhere in the
 *       COBOL estate - so the URL is a deployment-time input, and its absence has to be reported
 *       rather than defaulted.</li>
 *   <li>{@link DatasetBindings#binding(String)} refuses an unknown DD name rather than returning
 *       {@code null}. Every dataset name lives in {@code application.yml} and none is hard-coded in
 *       Java, so a typo in a DD name must fail where it is looked up.</li>
 *   <li>{@link DatasetBindings#validate()} refuses a catalogue that is not exactly the twenty-seven
 *       DD names the migrated code reads, or whose entries are internally incoherent. A width or
 *       location that is wrong reads and writes the wrong bytes without throwing, so the point of use
 *       is the worst place to discover it.</li>
 *   <li>{@link JobSubmissionProperties#validate()} refuses a job-submission destination that is
 *       relative, traversing, inside a read-only reference tree or outside its approved root, and
 *       refuses a byte contract that differs from {@code TDQUEUE(JOBS)} in
 *       {@code app/csd/CARDDEMO.CSD}. The destination is externally supplied and the writer appends
 *       to it in {@code MOD}, so a wrong value does not fail - it succeeds against the wrong
 *       file.</li>
 *   <li>{@link WebConfig.CobolErrorHandler} answers every failure family that carries no parity text
 *       with a fixed sentence rather than the exception's own message, while preserving the status an
 *       exception already carries. The failure it prevents is a disclosure, which likewise produces
 *       running software and a wrong response body rather than an error.</li>
 * </ul>
 *
 * <h2>Scope</h2>
 * These are plain unit tests: no Spring context is started, no {@code @SpringBootTest} is used and
 * nothing touches a network or a filesystem. Each of the three guards is driven on both sides, which
 * is what a threshold on branch coverage - rather than line coverage - actually asks for.
 */
@DisplayName("config - the startup guards that refuse a silently wrong default")
class ConfigBranchCoverageTest {

    /**
     * An obviously synthetic dataset location, used wherever a test needs a valid one and does not
     * care what it is. It could not be mistaken for a default supplied from inside Java, which is the
     * property several of these assertions turn on.
     */
    private static final String LOCATION = "SENTINEL.CATALOGUE.ENTRY";

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
            // Both take the same arm. The point of having this arm at all is the key: left to the
            // JDK, an emptied property produced a bare IllegalCharsetNameException quoting only the
            // empty name, which does not tell an operator which key to fix.
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
            // The catch translates a diagnostic; it never recovers. One exception type covers every
            // way a charset name can be unusable, and every message names the key and the value.
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
            // The other half of the derived path: Spring Boot recognises this scheme and names a
            // driver for it, but that driver is not a dependency of this module - so determination
            // succeeds and presence does not. The diagnostic must point at the URL rather than at a
            // driver property nobody set.
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
            // The whole point of the guard. Spring Boot's own determination would hand this
            // deployment H2's driver, because H2 is on the classpath at test scope, and a byte-parity
            // comparison against an empty in-memory database is worse than no comparison at all.
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("jdbc:carddemo-vsam://mainframe/PROD");

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("No JDBC driver class could be determined")
                    .withMessageContaining("NO FALLBACK IS APPLIED");
        }
    }

    /**
     * {@link DatasetBindings#validate()} - the catalogue-wide startup check.
     *
     * <p>Driven by direct call rather than through a context, so every arm is reachable without
     * Spring. The context-level equivalents live in {@code DataSourceConfigTest}; these exist because
     * the catalogue's validity rules are the kind of logic that has to be cheap to exercise
     * exhaustively, and because two of them - the alternate-index relationship checks - are
     * relationships between entries that no per-property constraint could express.
     */
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

            // An extra entry on top of a complete catalogue is rejected on its own account. It is not
            // harmless: nothing in the module will ever read it, so it is either a dataset somebody
            // added without a consumer or a name that was meant to replace one of the twenty-seven.
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
            // An absent organization is rejected for the same reason a wrong one is: the access path
            // is not something to infer from the other components.
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
            // Both are meaningful rather than merely unset: no DCB declares a format for some
            // datasets, and BLKSIZE=0 explicitly asks for a system-determined block size. Rejecting
            // either would force configuration to invent a value the JCL never states.
            assertThatNoException().isThrownBy(
                    withAcctdat(entry(LOCATION, "sequential", null, 0, 50, null))::validate);

            // And a declared key length is legitimate in its own right - on an entry that is keyed,
            // which is the only kind of entry a key length describes.
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

        /** A complete, coherent catalogue: the 27 required DD names with valid components. */
        private DatasetBindings validCatalogue() {
            DatasetBindings bindings = new DatasetBindings();
            Map<String, String> bases = Map.of(
                    "CARDAIX", "CARDDAT", "CXACAIX", "CCXREF", "XREFFIL1", "CCXREF");
            for (String ddName : DatasetBindings.REQUIRED_DD_NAMES.stream().sorted().toList()) {
                String base = bases.get(ddName);
                if (base != null) {
                    bindings.put(ddName, path(base));
                } else if (bases.containsValue(ddName)) {
                    // A cluster an alternate-index path indexes: indexed and keyed, because a path is
                    // a second access path over the same records rather than a second dataset.
                    bindings.put(ddName, baseCluster());
                } else {
                    bindings.put(ddName, entry(LOCATION, "sequential", "FB", null, 50, null));
                }
            }
            return bindings;
        }

        /** The valid catalogue with the account master's entry replaced. */
        private DatasetBindings withAcctdat(DatasetBinding binding) {
            DatasetBindings bindings = validCatalogue();
            bindings.put("ACCTDAT", binding);
            return bindings;
        }

        /** The valid catalogue with the card alternate-index path's entry replaced. */
        private DatasetBindings withCardaix(DatasetBinding binding) {
            DatasetBindings bindings = validCatalogue();
            bindings.put("CARDAIX", binding);
            return bindings;
        }

        /** A non-path entry with the given components and no alternate-index relationship. */
        private static DatasetBinding entry(String dsname, String organization, String recordFormat,
                Integer blockSize, int recordLength, Integer keyLength) {
            return new DatasetBinding(dsname, organization, false, recordFormat, blockSize,
                    recordLength, "CVACT01Y", keyLength, null, null, null);
        }

        /**
         * A valid alternate-index path entry over the named base.
         *
         * <p>A keyed entry declares where its key is: an alternate-index path is read by key, so the
         * catalogue check requires a key length and a key span that lies inside the record.
         */
        private static DatasetBinding path(String base) {
            return new DatasetBinding(LOCATION, "aix-path", false, "FB", null, 50, "CVACT02Y",
                    11, 0, base, "SENTINEL-ALT-KEY");
        }

        /** A valid base cluster: indexed, and keyed, because a path may only index a KSDS. */
        private static DatasetBinding baseCluster() {
            return new DatasetBinding(LOCATION, "ksds", false, "FB", null, 50, "CVACT02Y",
                    11, 0, null, null);
        }
    }

    /**
     * {@link JobContracts#validate(DatasetBindings)} - the job-contract graph's startup check.
     *
     * <p>The same reasoning as the dataset catalogue, one layer up. A job whose contract is wrong does
     * not throw when the job is built: it runs, against the wrong dataset or with the wrong step
     * gating, and the first sign of trouble is output that does not match. These assertions drive every
     * arm by direct call, including the two that need both catalogues at once - an alias is only
     * meaningful relative to the global DD-name catalogue.
     */
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
            withATenth.put("date-utility-job", job("CSUTLDTC"));
            assertThatIllegalStateException().isThrownBy(() -> withATenth.validate(validCatalogue()))
                    .withMessageContaining("Missing: []")
                    .withMessageContaining("Unexpected: [date-utility-job]");
        }

        @Test
        @DisplayName("a job re-pointed at another program is refused, because the pairing is fixed by "
                + "the source")
        void aJobRePointedAtAnotherProgramIsRefused() {
            JobContracts rePointed = validJobs();
            rePointed.put("account-balance-job", job("CBACT02C"));

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
                    .withMessageContaining("declares no name or no program");
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
            // app/cbl/CBACT04C.cbl:178 declares PARM-DATE PIC X(10) and L476-L480 contributes its
            // whole width to a fixed PIC X(16) TRAN-ID, so a value of any other length misplaces the
            // generated suffix in every transaction the job writes - and nothing would fail at run
            // time.
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
                + "refused")
        void aStepMissingEitherHalfIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobsWith("account-balance-job", new JobContract("CBACT01C",
                            List.of(), List.of(new StepContract("STEP05", " ", false)), null,
                            Map.of())).validate(validCatalogue()))
                    .withMessageContaining("declares no name or no program");
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

        /** The nine required jobs, each with its own program and one ungated step. */
        private JobContracts validJobs() {
            JobContracts contracts = new JobContracts();
            JobContracts.REQUIRED_JOBS.forEach((jobKey, program) -> contracts.put(jobKey,
                    JobContracts.PARAMETERISED_JOB.equals(jobKey)
                            ? interestCalcWith("2022071800")
                            : job(program)));
            return contracts;
        }

        /** The nine jobs with one entry replaced. */
        private JobContracts jobsWith(String jobKey, JobContract contract) {
            JobContracts contracts = validJobs();
            contracts.put(jobKey, contract);
            return contracts;
        }

        /** The nine jobs with one job-scoped dataset override added to the statement job. */
        private JobContracts jobsWithOverride(String ddName, JobDatasetBinding override) {
            Map<String, JobDatasetBinding> overrides = new LinkedHashMap<>();
            overrides.put(ddName, override);
            return jobsWith("statement-generation-job-a", new JobContract("CBSTM03A", List.of(),
                    List.of(new StepContract("STEP040", "CBSTM03A", false)), null, overrides));
        }

        /** A minimal valid contract for the given program: no parameters, one ungated step. */
        private static JobContract job(String program) {
            return new JobContract(program, List.of(),
                    List.of(new StepContract("STEP05", program, false)), null, Map.of());
        }

        /** The interest calculator's contract carrying the given parameter value. */
        private static JobContract interestCalcWith(String parmDateValue) {
            return new JobContract("CBACT04C",
                    List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string",
                            parmDateValue)),
                    List.of(new StepContract("STEP15", "CBACT04C", false)), null, Map.of());
        }

        /** An override that is purely an alias of a global entry. */
        private static JobDatasetBinding alias(String globalKey) {
            return new JobDatasetBinding(globalKey, null, null, false, null, null, null, null, null, null,
                    null, null);
        }

        /** An override that declares its own dataset inline, with the given record width. */
        private static JobDatasetBinding inline(int recordLength) {
            return new JobDatasetBinding(null, LOCATION, "sequential", false, "FB", null,
                    recordLength, "CVTRA05Y", null, null, null, null);
        }

        /** An inline override with a valid width and the given block size. */
        private static JobDatasetBinding inlineWithBlockSize(int blockSize) {
            return new JobDatasetBinding(null, LOCATION, "sequential", false, "FB", blockSize, 350,
                    "CVTRA05Y", null, null, null, null);
        }

        /** A complete, coherent dataset catalogue for aliases to resolve through. */
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

    /**
     * {@link BatchConfig.ParmDateJobParametersValidator} - the launch-time width check.
     *
     * <p>The same width rule as the configured contract, enforced at the other end: a job launched
     * programmatically supplies its own parameters, and those must satisfy the COBOL field's width just
     * as a configured value does. Both ends matter because a job can be launched either way.
     */
    @Nested
    @DisplayName("ParmDateJobParametersValidator - the PARM lands in a PIC X(10) field")
    class ParmDateValidation {

        /** The validator under test; stateless, so one instance serves every assertion here. */
        private final JobParametersValidator validator =
                new BatchConfig.ParmDateJobParametersValidator();

        @Test
        @DisplayName("the JCL's own value is accepted")
        void theJclValueIsAccepted() throws JobParametersInvalidException {
            // app/jcl/INTCALC.jcl:22 - PARM='2022071800'.
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
            // A future parameter, if the estate ever grew one, would carry its own COBOL field's width
            // and not this one's - so a differently-named parameter is checked for type and value but
            // not for ten characters. Asserting that keeps the rule honest rather than incidental.
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
            // Both agree, which is the point: the field is PIC X(10) and the JCL supplies 10
            // characters. Pinning the constant means a future edit cannot quietly widen one without
            // the other.
            assertThat(BatchConfig.PARM_DATE_WIDTH).isEqualTo(10);
            assertThat("2022071800".length()).isEqualTo(BatchConfig.PARM_DATE_WIDTH);
        }
    }

    /**
     * The <em>shipped</em> configuration documents, validated exactly as the application validates
     * them.
     *
     * <p>The one group in this file that starts a Spring context, and it earns the exception. Every
     * other assertion here proves the guards behave correctly for a configuration handed to them; this
     * one proves the configuration this module actually ships passes those guards - which is a
     * different claim, and the one a deployment depends on. Both catalogues are strict now, so a
     * mis-typed key or an incoherent entry in either document would fail here rather than in
     * production.
     */
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
        @DisplayName("both shipped profiles satisfy the job-submission contract")
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
                // Compared with Path's own segment-wise operations rather than AssertJ's path
                // assertions: those canonicalise through toRealPath, which would require the
                // destination to already exist on disk. Nothing here touches the filesystem, exactly
                // as the validation itself does not.
                Path resolved = port.destinationPath();
                assertThat(resolved.isAbsolute()).isTrue();
                assertThat(resolved.startsWith(Paths.get(port.approvedRoot()).normalize())).isTrue();
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
            // The whole point: two contexts, two databases. schema-h2.sql has no IF NOT EXISTS, so
            // a shared name plus initialize-schema: always would fail the second refresh.
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
                // Stable, not random: every reference to work-dir must land in one directory, so
                // the run's thirty-odd outputs cannot scatter.
                assertThat(context.getEnvironment().getProperty("carddemo.test.work-dir"))
                        .isEqualTo(workDir);
                // The Maven build supplies the run id as a system property, which outranks the
                // profile's literal default; outside Maven the honest fallback stands.
                assertThat(runId).isNotBlank();
                assertThat(cloneId).isNotBlank();

                JobSubmissionProperties port = context.getBean(JobSubmissionProperties.class);
                assertThat(port.destination()).startsWith(workDir);
                assertThat(port.approvedRoot()).isEqualTo(workDir);
            });
        }

        /**
         * Builds a runner over the real {@code application.yml}, optionally activating a profile.
         *
         * <p>{@link RandomValuePropertySource} is installed because the test profile's datasource URL
         * carries a {@code ${random.uuid}} to give each context its own database.
         * {@code SpringApplication} adds that source itself, but a hand-built context does not get
         * it - so a runner that omitted it would fail to resolve the placeholder and would look like
         * a configuration defect rather than a missing test fixture.
         *
         * @param profile {@code "default"} for the base document alone, otherwise the profile to
         *                activate on top of it
         * @return the configured runner
         */
        private ApplicationContextRunner runnerFor(String profile) {
            ApplicationContextRunner runner = new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(context -> RandomValuePropertySource
                            .addToEnvironment(context.getEnvironment()))
                    .withConfiguration(AutoConfigurations.of(
                            PropertyPlaceholderAutoConfiguration.class))
                    .withUserConfiguration(ShippedCatalogues.class);
            if (!"default".equals(profile)) {
                runner = runner.withPropertyValues("spring.profiles.active=" + profile);
            }
            return runner;
        }

        /**
         * Binds {@code spring.datasource} without building a pool, so the URL can be read as the
         * profile resolved it.
         */
        @Configuration
        @EnableConfigurationProperties(DataSourceProperties.class)
        static class BoundDataSourceProperties {
        }

        /**
         * The three strict bound types on their own, with no datasource, batch or web infrastructure
         * in the context - so a failure here can only be about the configuration documents.
         */
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

    /**
     * The error mapping's two obligations: publish no message a library or driver wrote, and do not
     * change a status an exception already decided.
     *
     * <p>Every assertion here uses a deliberately conspicuous message on the exception it constructs,
     * so a leak shows up as that exact token appearing in a response body rather than as a subtle
     * difference in wording.
     */
    @Nested
    @DisplayName("CobolErrorHandler - publishes fixed text, and preserves a status it did not choose")
    class SanitizedErrorResponses {

        /**
         * A token no fixed message contains, planted in every exception message these tests build. If
         * it ever reaches a response body, a handler read the exception instead of a constant.
         */
        private static final String LEAK = "SENTINEL-INTERNAL-DETAIL";

        private final CobolErrorHandler handler = new CobolErrorHandler();

        @Test
        @DisplayName("an unreadable request body is answered 400 with fixed text and no leak")
        void anUnreadableBodyIsSanitized() {
            HttpMessageNotReadableException unreadable =
                    new HttpMessageNotReadableException(LEAK, emptyRequest());

            ResponseEntity<FailureResponse> response =
                    CobolErrorHandler.handleUnreadableBody(unreadable);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(400);
            assertThat(response.getBody().error()).isEqualTo("Bad Request");
            assertThat(response.getBody().message())
                    .isEqualTo(CobolErrorHandler.UNREADABLE_BODY_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("a type mismatch is answered 400 with fixed text and no leak")
        void aTypeMismatchIsSanitized() {
            TypeMismatchException mismatch = new TypeMismatchException(LEAK, Integer.class);

            ResponseEntity<FailureResponse> response = handler.handleTypeMismatch(mismatch);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(CobolErrorHandler.TYPE_MISMATCH_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("a data-access failure is answered 500 with fixed text and no leak")
        void aDataAccessFailureIsSanitized() {
            DataAccessResourceFailureException failure =
                    new DataAccessResourceFailureException(LEAK);

            ResponseEntity<FailureResponse> response = handler.handleDataAccessFailure(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(500);
            assertThat(response.getBody().error()).isEqualTo("Internal Server Error");
            assertThat(response.getBody().message())
                    .isEqualTo(CobolErrorHandler.DATASET_ACCESS_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("an unclaimed failure carrying no status becomes 500 with fixed text")
        void anUnclaimedFailureBecomes500() {
            ResponseEntity<FailureResponse> response =
                    handler.handleUnexpectedFailure(new IllegalArgumentException(LEAK));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("a configuration failure surfacing at request time discloses no configuration")
        void aConfigurationFailureIsSanitized() {
            // The shape DatasetBindings.binding throws for an unknown DD name: its message lists every
            // configured DD name, which is the dataset inventory. Correct at startup, where an operator
            // reads it; not something to publish to a caller if it ever surfaces mid-request.
            IllegalStateException configurationFault = new IllegalStateException(
                    "No dataset binding is configured for DD name '" + LEAK + "'");

            ResponseEntity<FailureResponse> response =
                    handler.handleUnexpectedFailure(configurationFault);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE)
                    .doesNotContain(LEAK);
        }

        @Test
        @DisplayName("the catch-all preserves a status the failure already carries - 404 stays 404")
        void theCatchAllPreservesACarriedStatus() {
            ResponseStatusException notFound =
                    new ResponseStatusException(HttpStatus.NOT_FOUND, LEAK);

            ResponseEntity<FailureResponse> response = handler.handleUnexpectedFailure(notFound);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(404);
            assertThat(response.getBody().error()).isEqualTo("Not Found");
            assertThat(response.getBody().message())
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
            FailureResponse body = CobolErrorHandler.sanitizedBody(unregistered,
                    CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE);
            assertThat(body.status()).isEqualTo(599);
            assertThat(body.error()).isEmpty();
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

    /**
     * {@code statusForOutcome} maps the five-valued repository vocabulary onto a status, and the
     * division it draws is the COBOL's own: the four outcomes a guard chain handles in-program leave
     * the request successful, and {@code WHEN OTHER} does not.
     */
    @Nested
    @DisplayName("CobolErrorHandler.statusForOutcome - only WHEN OTHER is a server error")
    class RepositoryOutcomeStatuses {

        @ParameterizedTest(name = "{0} is an outcome the COBOL handles in-program, so 200")
        @EnumSource(value = FileStatus.Outcome.class,
                names = { "OK", "END_OF_FILE", "NOT_FOUND", "DUPLICATE" })
        @DisplayName("the four in-program outcomes are 200, so the program's own text survives")
        void inProgramOutcomesAreOk(FileStatus.Outcome outcome) {
            assertThat(CobolErrorHandler.statusForOutcome(outcome)).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("OTHER is the WHEN OTHER arm every guard chain abends on, so 500")
        void otherIsAServerError() {
            assertThat(CobolErrorHandler.statusForOutcome(FileStatus.Outcome.OTHER))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The job-submission port's contract. Two things are being protected: the four values transcribed
     * from {@code TDQUEUE(JOBS)}, which are what make the emitted bytes match the CICS write, and the
     * externally supplied destination, which the writer appends to in {@code MOD}.
     */
    @Nested
    @DisplayName("JobSubmissionProperties.validate - the CSD byte contract and a safe destination")
    class JobSubmissionContract {

        private static final String ROOT = "/var/carddemo";
        private static final String TARGET = "/var/carddemo/inreader/JOBS";

        /**
         * Builds a contract that differs from the shipped one only in the two paths.
         *
         * @param root   the approved root
         * @param target the destination
         * @return the contract
         */
        private JobSubmissionProperties paths(String root, String target) {
            return new JobSubmissionProperties("JOBS", "INREADER",
                    JobSubmissionProperties.TDQ_RECORD_LENGTH,
                    JobSubmissionProperties.TDQ_RECORD_FORMAT,
                    JobSubmissionProperties.TDQ_BLOCK_FORMAT,
                    JobSubmissionProperties.TDQ_DISPOSITION, root, target);
        }

        /**
         * Builds a contract whose byte contract is overridden and whose paths are valid.
         *
         * @param length the record length
         * @param format the record format
         * @param block  the block format
         * @param mode   the disposition
         * @return the contract
         */
        private JobSubmissionProperties bytes(int length, String format, String block, String mode) {
            return new JobSubmissionProperties("JOBS", "INREADER", length, format, block, mode,
                    ROOT, TARGET);
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
                    80, "FIXED", "UNBLOCKED", "MOD", ROOT, TARGET);

            assertThatIllegalStateException().isThrownBy(port::validate)
                    .withMessageContaining("queue-name")
                    .withMessageContaining("TDQUEUE(JOBS)");
        }

        @Test
        @DisplayName("an absent DD name is refused, naming the CSD DDNAME it should carry")
        void theDdNameMustBePresent() {
            JobSubmissionProperties port = new JobSubmissionProperties("JOBS", null,
                    80, "FIXED", "UNBLOCKED", "MOD", ROOT, TARGET);

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

        /**
         * The port contract and the bean that validates it, with nothing else in the context - so a
         * failure here can only come from the binding or from the validation.
         */
        @Configuration
        @EnableConfigurationProperties(JobSubmissionProperties.class)
        static class PortOnly {

            /**
             * Publishes the startup validator, mirroring {@code WebConfig}'s own bean.
             *
             * @param properties the bound contract
             * @return the validator
             */
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
    }

    /**
     * The four configuration classes wired together, under each shipped profile.
     *
     * <p>Every other group here drives one guard in isolation, which is the right way to prove a
     * decision. This one answers a different question: whether the four classes still form a working
     * context <em>together</em>, with the real configuration documents behind them. The application
     * entry class does not exist yet - it belongs to a later part of the build - so this slice is the
     * largest context that can be assembled today, and it covers the wiring that a full context would
     * otherwise be the first thing to exercise.
     *
     * <p>Both profiles are asserted, and they are expected to behave <em>differently</em>: the default
     * profile must refuse to start, because it supplies no JDBC URL and the URL is a deployment-time
     * input rather than something to default; the test profile must start completely, on a database
     * of its own.
     */
    @Nested
    @DisplayName("the config package as a whole - both profiles behave as designed")
    class TheConfigurationPackageWiresUp {

        /**
         * Builds a runner over the real configuration documents with all four config classes.
         *
         * @param profile {@code "default"} for the base document alone, otherwise the profile to
         *                activate
         * @return the configured runner
         */
        private ApplicationContextRunner runnerFor(String profile) {
            ApplicationContextRunner runner = new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(context -> RandomValuePropertySource
                            .addToEnvironment(context.getEnvironment()))
                    .withConfiguration(AutoConfigurations.of(
                            PropertyPlaceholderAutoConfiguration.class))
                    .withUserConfiguration(CobolCharsetConfig.class, DataSourceConfig.class,
                            BatchConfig.class, WebConfig.class);
            if (!"default".equals(profile)) {
                runner = runner.withPropertyValues("spring.profiles.active=" + profile);
            }
            return runner;
        }

        @Test
        @DisplayName("the default profile refuses to start without a deployment-supplied JDBC URL")
        void theDefaultProfileRefusesToStartWithoutAUrl() {
            // Not a defect: no JDBC driver coordinate is pinned in this module, the datasets are VSAM
            // and sequential files, and the URL is supplied at deployment. Refusing is the designed
            // outcome, and it must refuse rather than fall back to the H2 driver on the test
            // classpath.
            runnerFor("default").run(context -> assertThat(context).getFailure()
                    .rootCause()
                    .hasMessageContaining("spring.datasource.url"));
        }

        @Test
        @DisplayName("the test profile wires all four config classes on a database of its own")
        void theTestProfileWiresEveryConfigClass() {
            runnerFor("test").run(context -> {
                assertThat(context).hasNotFailed();

                // CobolCharsetConfig: three named code pages, the active one profile-supplied.
                assertThat(context).hasBean("carddemoEbcdicCharset");
                assertThat(context).hasBean("carddemoAsciiCharset");
                assertThat(context.getBean("carddemoDatasetCharset", Charset.class))
                        .isEqualTo(Charset.forName("US-ASCII"));

                // DataSourceConfig: a real pool on this context's own database, plus the strict
                // 27-entry catalogue.
                HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                assertThat(pool.getJdbcUrl()).startsWith("jdbc:h2:mem:carddemo_test_");
                assertThat(pool.getDriverClassName()).isEqualTo("org.h2.Driver");
                assertThat(context.getBean(DatasetBindings.class))
                        .hasSize(DatasetBindings.REQUIRED_DD_NAMES.size());

                // BatchConfig: the nine validated job contracts and the transaction manager.
                assertThat(context.getBean(JobContracts.class))
                        .hasSize(JobContracts.REQUIRED_JOBS.size());
                assertThat(context).hasSingleBean(PlatformTransactionManager.class);
                assertThat(context).hasBean("jobContractValidator");

                // WebConfig: the single Clock, the Jackson customizer, the advice, and the validated
                // job-submission port.
                assertThat(context).hasSingleBean(Clock.class);
                assertThat(context).hasSingleBean(Jackson2ObjectMapperBuilderCustomizer.class);
                assertThat(context).hasSingleBean(CobolErrorHandler.class);
                assertThat(context).hasBean("jobSubmissionValidator");
                assertThat(context.getBean(JobSubmissionProperties.class).recordLength())
                        .isEqualTo(JobSubmissionProperties.TDQ_RECORD_LENGTH);
            });
        }
    }

    /**
     * An empty HTTP request body, for constructing a parse failure without a servlet container.
     *
     * @return a request carrying no headers and an empty body
     */
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
