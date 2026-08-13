package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tests for {@link DataSourceConfig}: the module's single data-access seam - one pooled
 * {@link DataSource}, one {@link JdbcTemplate}, and the DD-name-keyed catalogue of dataset bindings
 * that replaces every hard-coded mainframe dataset name in the migrated code.
 *
 * <h2>What this suite is responsible for</h2>
 * <p>Wiring and binding, and nothing else. It answers four questions about the configuration class
 * and leaves every other question to the test that owns it:
 * <ol>
 *   <li>does the catalogue bind <em>completely</em> and with the right geometry;</li>
 *   <li>are the alias and alternate-index relationships preserved rather than collapsed;</li>
 *   <li>does the keyed lookup fail fast, audibly, on a DD name nobody configured;</li>
 *   <li>is there exactly one pool, exactly one template over it, and <em>nothing else</em>.</li>
 * </ol>
 *
 * <p>Deliberately out of scope here: repository behaviour, SQL, and the fixed-width record codecs -
 * those belong to the domain packages and to {@code common}. So does the whole-graph context load,
 * which the application entry-point test owns; this suite uses
 * {@link ApplicationContextRunner} slices instead, so every branch in
 * {@code DataSourceConfig} is reachable in milliseconds and the per-package branch-coverage gate is
 * met without standing up the application.
 *
 * <h2>Why the negative assertions matter as much as the positive ones</h2>
 * <p>{@code DataSourceConfig} is defined as much by what it refuses to declare as by what it
 * declares. It contributes no transaction manager - exactly one exists in this module and the batch
 * configuration owns it, because Spring Batch and Spring JDBC both resolve one by type and a second
 * definition would make that resolution ambiguous. It contributes no data-definition behaviour, no
 * schema initialiser, no object-relational mapping and no generated index. A suite that asserted
 * only the positives would pass against an implementation that had quietly grown any of those, so
 * {@link NegativeContract} pins the bean set <strong>exhaustively</strong>: five beans, named and
 * typed. Exhaustiveness is the proof, which is also why that group never enumerates the artefacts it
 * forbids - a prohibition notice that quoted them would register as a hit in the negative scans that
 * police this module, and would give a genuine violation somewhere to hide.
 *
 * <h2>Where the expected values come from, and how they were verified</h2>
 * <p>Every expectation below traces to a reference file in this checkout, and every citation was
 * re-verified against it rather than taken on trust:
 *
 * <ul>
 *   <li><strong>The 8 CICS {@code FILE} definitions</strong> - {@code app/csd/CARDDEMO.CSD}, one
 *       {@code DEFINE FILE} block each: {@code ACCTDAT} L1, {@code CARDAIX} L13, {@code CARDDAT}
 *       L25, {@code CCXREF} L37, {@code CUSTDAT} L50, {@code CXACAIX} L63, {@code TRANSACT} L76,
 *       {@code USRSEC} L88. Note when re-checking that every {@code DEFINE} line in that file begins
 *       with a leading space.</li>
 *   <li><strong>The batch DD names and their geometry</strong> - {@code app/jcl}:
 *       {@code READACCT.jcl} L22/L25-L26, {@code READCARD.jcl} L22/L25-L26, {@code READXREF.jcl}
 *       L22/L25-L26, {@code READCUST.jcl} L6/L9-L10, {@code INTCALC.jcl} L22-L41,
 *       {@code POSTTRAN.jcl} L23-L42, {@code TRANREPT.jcl} L65-L80 and {@code CREASTMT.JCL}
 *       L29-L39/L67-L71/L83-L96.</li>
 *   <li><strong>Record widths</strong> - corroborated independently by {@code README.md} L67-L80,
 *       which tabulates the same widths against the same {@code app/cpy} members, and by
 *       {@code app/catlg/LISTCAT.txt}.</li>
 * </ul>
 *
 * <p>Those trees are read-only: they are the sole oracle for behavioural equivalence, and this suite
 * writes to none of them. It also declares no configuration document of its own. The shipped
 * {@code application.yml} and its {@code test} profile document are the only property sources used,
 * loaded through {@link ConfigDataApplicationContextInitializer}; a second copy under the test tree
 * would shadow them unpredictably through classpath ordering, and would let this suite pass while
 * the configuration the application actually ships was wrong. Where an isolated property set is
 * needed it is supplied inline instead, through
 * {@link ApplicationContextRunner#withPropertyValues(String...)}.
 *
 * <h2>The JDBC driver is a deployment-time input</h2>
 * <p>The build pins <strong>no</strong> JDBC driver coordinate, deliberately: there is not one
 * {@code EXEC SQL} statement in any of the twenty-eight COBOL programs, the estate is entirely VSAM
 * and sequential files, and indexed VSAM has no standard published JDBC driver. The URL, the driver
 * class and the credentials are therefore supplied at deployment time, and
 * <strong>production connectivity cannot be exercised in this environment.</strong> That limitation
 * is recorded here rather than absorbed: this suite does not pretend to reach a mainframe backend,
 * is not disabled or skipped to avoid the question, and exercises the one driver that is genuinely
 * available - the test-scope in-memory database, which is also what
 * {@link DataSourceAndJdbcTemplateWiring} uses to prove that the driver class is <em>inferred from
 * the URL</em> rather than pinned in Java.
 *
 * <h2>Two conflicts in the legacy sources, documented rather than normalised</h2>
 * <p>Both are asserted in the direction the migration resolved them, and both are recorded so that
 * a later reader does not "fix" the assertion:
 * <ul>
 *   <li>The HTML statement output is declared <strong>twice</strong> in one job with different
 *       widths - {@code CREASTMT.JCL} L67-L71 (the {@code IEFBR14} pre-delete step) says
 *       {@code LRECL=80,BLKSIZE=3200}, while L92-L96, the step that actually <em>creates</em> the
 *       file, says {@code LRECL=100,BLKSIZE=800}. The creating step is authoritative: 100.</li>
 *   <li>All eight CICS definitions declare {@code RECORDFORMAT(V)} while the batch JCL declares
 *       {@code RECFM=F} or {@code RECFM=FB} for the very same datasets. The Java layer models no
 *       variable-length record and treats every width as copybook-fixed, so the configured record
 *       format is the JCL's - the concrete, byte-level contract. This suite asserts what the
 *       configuration declares, verbatim, and performs no reconciliation of its own.</li>
 * </ul>
 *
 * <h2>User-specified rules</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single
 * line is the whole document, so <strong>no user rule governs this file</strong>. Its absence is not
 * licence to lower the bar; the migration plan elevates twelve enterprise practices to binding
 * constraints in their place, and the ones bearing on this file are B1 (no dependency is added - only
 * JUnit 5, AssertJ, Spring Test and the test-scope database already on the closed list are used),
 * B3 (the reference trees are read-only and appear here only as provenance), B4 (no scope creep - no
 * configuration document is created under the test tree), B7 (deterministic and non-interactive: no
 * wall clock, no network, no watch mode), B8 (explicit over implicit - explicit imports, no
 * wildcards, and every dataset name asserted through configuration rather than through a literal),
 * B9 (no static mutable state - every constant is immutable and every runner is built per test) and
 * B12 (the inability to reach a production backend is documented above, not absorbed).
 *
 * @see DataSourceConfig
 * @see DatasetBindings
 * @see DatasetBinding
 */
class DataSourceConfigTest {

    /**
     * The eight CICS {@code FILE} definitions, in {@code app/csd/CARDDEMO.CSD} order.
     *
     * <p>Two of the eight - the card and cross-reference alternate indexes - are access
     * <em>paths</em> over another entry's base cluster rather than datasets in their own right. They
     * are listed here because the CSD defines them as files, and their path-ness is asserted
     * separately in {@link AliasAndAlternateIndexEquivalence}.
     */
    private static final List<String> CSD_FILE_KEYS = List.of(
            "ACCTDAT", "CARDAIX", "CARDDAT", "CCXREF", "CUSTDAT", "CXACAIX", "TRANSACT", "USRSEC");

    /**
     * The batch DD names under which {@code app/jcl} addresses those same eight datasets.
     *
     * <p>Each exists because the COBOL and the JCL address one dataset under several names, and
     * losing a name would leave a program unable to resolve its own DD. The cross-reference base
     * alone is opened as {@code XREFFILE} and as {@code CARDXREF}, and its alternate-index path as
     * {@code XREFFIL1}.
     */
    private static final List<String> BATCH_ALIAS_KEYS = List.of(
            "ACCTFILE", "CARDFILE", "CUSTFILE", "XREFFILE", "XREFFIL1", "CARDXREF", "TRANFILE");

    /**
     * The twelve datasets that exist only in batch - no CICS {@code FILE} definition names any of
     * them - read from the {@code DD} statements of {@code app/jcl} and {@code app/proc}.
     */
    private static final List<String> BATCH_ONLY_KEYS = List.of(
            "DALYTRAN", "DALYREJS", "TCATBALF", "DISCGRP", "TRANTYPE", "TRANCATG", "DATEPARM",
            "TRNXFILE", "TRANREPT", "STMTFILE", "HTMLFILE", "SYSTRAN");

    /** All twenty-seven configured DD names: the eight CICS files, seven aliases and twelve more. */
    private static final List<String> ALL_DATASET_KEYS =
            Stream.of(CSD_FILE_KEYS, BATCH_ALIAS_KEYS, BATCH_ONLY_KEYS).flatMap(List::stream).toList();

    /**
     * The three entries the configuration declares as alternate-index paths, each over a base
     * cluster declared by another entry.
     */
    private static final List<String> ALTERNATE_INDEX_PATH_KEYS =
            List.of("CARDAIX", "CXACAIX", "XREFFIL1");

    /** The five entries whose JCL {@code DCB} declares a block size. */
    private static final List<String> KEYS_WITH_A_DECLARED_BLOCK_SIZE =
            List.of("DALYREJS", "TRANREPT", "STMTFILE", "HTMLFILE", "SYSTRAN");

    /**
     * The three outputs the JCL declares as a relative generation - {@code NAME(+1)} - so that a
     * write creates a new generation rather than replacing one.
     */
    private static final List<String> GENERATION_DATA_GROUP_KEYS =
            List.of("DALYREJS", "TRANREPT", "SYSTRAN");

    /**
     * The property that gives the pool a URL it can actually build against.
     *
     * <p>The shipped default profile spells the URL as an environment placeholder with an empty
     * default, so on its own it cannot build a pool - by design, and asserted in
     * {@link DataSourceAndJdbcTemplateWiring#absentUrlRefusesToStartNamingTheMissingProperty()}.
     * Every test that needs a live bean graph therefore supplies this one inline property, which is
     * also the whole point: the driver class is never named, only the URL, so the context can only
     * start if the driver is inferred rather than pinned.
     */
    private static final String IN_MEMORY_URL_PROPERTY =
            "spring.datasource.url=jdbc:h2:mem:carddemo_datasourceconfigtest;DB_CLOSE_DELAY=-1";

    /**
     * The record-image representation, supplied inline by the no-document slices.
     *
     * <p>{@value RecordImageForm#FORM_PROPERTY} carries no default, so a slice with no configuration
     * document has to state it or no bean graph can be built at all - which is the property
     * {@link RecordImageRepresentation} asserts directly.
     */
    private static final String RECORD_IMAGE_FORM_PROPERTY =
            RecordImageForm.FORM_PROPERTY + "=CHARACTER";

    /**
     * The physical-record ordinal, supplied inline by the no-document slices.
     *
     * <p>{@value PhysicalSequence#EXPRESSION_PROPERTY} carries no default for the same reason
     * {@value RecordImageForm#FORM_PROPERTY} does, so a slice with no configuration document has to
     * state it before any bean graph can be built - which is the property
     * {@link PhysicalRecordOrdinal} asserts directly.
     */
    private static final String PHYSICAL_SEQUENCE_PROPERTY =
            PhysicalSequence.EXPRESSION_PROPERTY + "=_ROWID_";

    /**
     * What makes a "shipped default profile" slice actually mean the default profile.
     *
     * <p>A slice below that asks for the default profile is asking to see {@code application.yml}
     * <em>alone</em>. It does not get that for free. An {@link ApplicationContextRunner} builds its
     * environment on top of the surrounding JVM's system properties and process environment, so a
     * build invoked as {@code mvn test -Dspring.profiles.active=test}, or by a CI executor that
     * exports {@code SPRING_PROFILES_ACTIVE=test} - a common convention - activates the {@code test}
     * profile <em>inside</em> the runner. {@code application-test.yml} then loads on top of the
     * document under test and its values win, and every assertion about a default-profile value
     * reads a test-profile value instead: {@code carddemo-test-pool} for the pool name,
     * {@code CARDDEMO.TEST.*} for every dataset location, {@code always} for the batch initialiser,
     * and a URL where the shipped document deliberately supplies none.
     *
     * <p>Stating the key with an empty value is what closes that: {@code withPropertyValues} installs
     * it as the first property source in the environment, ahead of both {@code systemProperties} and
     * {@code systemEnvironment}, and an empty value means no active profile at all. The alternative -
     * clearing the system property for the duration of the run - was measured and rejected: it fixes
     * the {@code -D} form and leaves the environment-variable form leaking, so it closes half the
     * hole. Nothing global is mutated either way, so these slices stay independent of each other and
     * of run order (practice B7).
     *
     * <p>Only the default-profile arms need this. An arm that names a profile has already said what it
     * wants and outranks the inherited value by the same precedence rule.
     */
    private static final String NO_EXTERNALLY_ACTIVATED_PROFILE = "spring.profiles.active=";

    /** JUnit factory for the twenty-seven configured DD names. */
    static Stream<String> allDatasetKeys() {
        return ALL_DATASET_KEYS.stream();
    }

    /** JUnit factory for the eight CICS {@code FILE} definitions. */
    static Stream<String> csdFileKeys() {
        return CSD_FILE_KEYS.stream();
    }

    /**
     * A slice over the shipped default profile: the real {@code application.yml} is the property
     * source, with only the URL supplied inline so that a pool can be built.
     *
     * <p>{@value #NO_EXTERNALLY_ACTIVATED_PROFILE} is supplied for the reason set out on that
     * constant - it is what makes this slice read {@code application.yml} alone whatever the
     * surrounding JVM was told about profiles.
     *
     * @return a runner over the shipped default document, with a buildable URL
     */
    private ApplicationContextRunner shippedDefaultProfile() {
        // No inherited environment to reproduce: the runner's own environment is the subject here.
        return shippedDefaultProfile(context -> { });
    }

    /**
     * The same slice, with an inherited environment installed before the configuration documents are
     * read.
     *
     * <p>This overload exists so the hermeticity assertion in
     * {@link DataSourceAndJdbcTemplateWiring#anExternallyActivatedProfileCannotReachTheDefaultSlice()}
     * can exercise <em>this</em> runner rather than a copy of it. A copy would let someone delete
     * {@value #NO_EXTERNALLY_ACTIVATED_PROFILE} from the helper above and leave that assertion green,
     * which is the one thing a regression guard must not permit. The initializer is registered
     * <strong>first</strong>, before {@link ConfigDataApplicationContextInitializer}, because profile
     * activation is resolved when the documents are loaded - a property source installed after that
     * point cannot influence which document was chosen, and an assertion built that way would pass
     * whether or not the fix were present.
     *
     * @param inheritedEnvironment installs whatever the surrounding process is imagined to have
     *                             supplied; a no-op for ordinary use
     * @return a runner over the shipped default document, with a buildable URL
     */
    private ApplicationContextRunner shippedDefaultProfile(
            ApplicationContextInitializer<ConfigurableApplicationContext> inheritedEnvironment) {
        return new ApplicationContextRunner()
                .withInitializer(inheritedEnvironment)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(IN_MEMORY_URL_PROPERTY, NO_EXTERNALLY_ACTIVATED_PROFILE);
    }

    /**
     * An initializer that reproduces {@code SPRING_PROFILES_ACTIVE=<profile>} in the process
     * environment, at the precedence position the real variable occupies.
     *
     * <p>Java cannot set its own environment variables, so the variable is reproduced as a
     * {@link SystemEnvironmentPropertySource} - the source type that performs the
     * {@code SPRING_PROFILES_ACTIVE} to {@code spring.profiles.active} relaxed-name mapping - inserted
     * immediately above {@code systemProperties}. That is the position that makes the assertion
     * discriminating: it outranks every configuration document, so an arm that does not state its
     * profile would follow it, while it still loses to the inline value an arm that <em>does</em> state
     * its profile installs. The inherited sources are left in place rather than replaced, so nothing
     * else the runner needs disappears.
     *
     * @param profile the profile the imagined executor exported
     * @return an initializer installing that variable
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext>
            processEnvironmentActivating(String profile) {
        return context -> context.getEnvironment().getPropertySources().addBefore(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("simulatedProcessEnvironment",
                        Map.of("SPRING_PROFILES_ACTIVE", profile)));
    }

    /**
     * A slice over the shipped {@code test} profile, which supplies its own in-memory URL and
     * repoints every dataset at a fixture. Nothing is supplied inline here at all: the profile
     * document is doing the whole job, which is what makes this the honest test of it.
     */
    private ApplicationContextRunner shippedTestProfile() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues("spring.profiles.active=test");
    }

    /**
     * A slice with <strong>no</strong> configuration document in the picture: only the properties
     * passed here exist. This is what proves that a value which is not configured is not silently
     * supplied from somewhere inside Java.
     */
    private ApplicationContextRunner withInlinePropertiesOnly(String... inlineProperties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(RECORD_IMAGE_FORM_PROPERTY, PHYSICAL_SEQUENCE_PROPERTY)
                .withPropertyValues(inlineProperties);
    }

    /**
     * The same no-document slice, plus a complete and valid dataset catalogue supplied inline.
     *
     * <p>Needed because the catalogue is validated at startup: it must declare exactly the
     * twenty-seven DD names the migrated code reads, so a slice that configures one entry - or none -
     * no longer starts. That is the intended behaviour, and it changes what a "minimal" inline
     * configuration is rather than what these tests are for. Each caller still proves the same
     * property it always did, against a catalogue that is now complete instead of partial.
     *
     * <p>The geometry below is deliberately <em>not</em> the shipped geometry. Every location is an
     * obvious sentinel and every width is the same 50 bytes, because these slices exist to prove that
     * values travel from configuration into the bindings - the real widths are asserted against the
     * shipped documents elsewhere in this file, which is where they belong.
     *
     * @param inlineProperties properties appended after the catalogue, so a caller can override any
     *                         entry's component simply by restating it
     * @return a runner over a valid catalogue plus the caller's own properties
     */
    private ApplicationContextRunner withValidCatalogueAnd(String... inlineProperties) {
        return withInlinePropertiesOnly(
                Stream.concat(minimalValidCatalogue(), Stream.of(inlineProperties))
                        .toArray(String[]::new));
    }

    /**
     * A complete, valid twenty-seven-entry catalogue as inline properties.
     *
     * <p>The three alternate-index paths carry their base and alternate key, because a path that named
     * neither would be rejected - the relationship is part of what makes a catalogue valid, not an
     * optional embellishment.
     *
     * @return one {@code key=value} property per component of every required DD name
     */
    private static Stream<String> minimalValidCatalogue() {
        Map<String, String> alternateIndexBases = Map.of(
                "CARDAIX", "CARDDAT", "CXACAIX", "CCXREF", "XREFFIL1", "CCXREF");
        return ALL_DATASET_KEYS.stream().flatMap(ddName -> {
            String prefix = "carddemo.datasets." + ddName + ".";
            String base = alternateIndexBases.get(ddName);
            boolean indexedByAPath = alternateIndexBases.containsValue(ddName);
            String organization = base != null ? "aix-path" : indexedByAPath ? "ksds" : "sequential";
            Stream<String> components = Stream.of(
                    prefix + "dsname=SENTINEL.CATALOGUE." + ddName,
                    prefix + "organization=" + organization,
                    prefix + "record-format=FB",
                    prefix + "record-length=50");
            if (base == null && !indexedByAPath) {
                return components;
            }
            // A keyed entry states where its key is, and a cluster an alternate-index path indexes is
            // keyed itself - a path is a second access path over the same records. The sentinel span
            // below sits inside the sentinel 50-byte record, which is all the catalogue check asks.
            Stream<String> keyGeometry = Stream.of(
                    prefix + "key-length=11",
                    prefix + "key-offset=0");
            return base == null
                    ? Stream.concat(components, keyGeometry)
                    : Stream.concat(Stream.concat(components, keyGeometry), Stream.of(
                            prefix + "base=" + base,
                            prefix + "alternate-key=SENTINEL-ALT-KEY"));
        });
    }

    /**
     * Copies a descriptor with a different location, so that two profiles' descriptors can be
     * compared on their geometry alone.
     *
     * @param binding  the descriptor to copy
     * @param location the location to substitute
     * @return an otherwise identical descriptor
     */
    private static DatasetBinding withLocation(DatasetBinding binding, String location) {
        // The canonical twelve-component constructor, deliberately: the eleven-component convenience
        // form defaults the reuse attribute to NOREUSE, which would silently erase the one REUSE
        // cluster in the estate and make this helper report a geometry change that never happened.
        return new DatasetBinding(location, binding.organization(), binding.gdg(),
                binding.recordFormat(), binding.blockSize(), binding.recordLength(),
                binding.copybook(), binding.keyLength(), binding.keyOffset(), binding.base(),
                binding.alternateKey(), binding.reusable());
    }

    @Nested
    @DisplayName("carddemo.datasets - the twenty-seven-entry dataset catalogue binds in full")
    class DatasetCatalogueCompleteness {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.config.DataSourceConfigTest#allDatasetKeys")
        @DisplayName("every configured DD name binds to a usable descriptor")
        void everyConfiguredDdNameBinds(String ddName) {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThat(bindings).containsKey(ddName);
                DatasetBinding binding = bindings.binding(ddName);
                assertThat(binding.dsname()).as("%s location", ddName).isNotBlank();
                assertThat(binding.recordLength()).as("%s record length", ddName).isPositive();
                assertThat(binding.organization()).as("%s organization", ddName)
                        .isIn("ksds", "aix-path", "sequential");
            });
        }

        @Test
        @DisplayName("the catalogue holds exactly twenty-seven keys: eight CICS files, seven batch "
                + "aliases and twelve batch-only datasets")
        void catalogueHoldsExactlyTheTwentySevenConfiguredKeys() {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(DatasetBindings.class).keySet())
                    .containsExactlyInAnyOrderElementsOf(ALL_DATASET_KEYS)
                    .hasSize(27));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.config.DataSourceConfigTest#csdFileKeys")
        @DisplayName("every CICS FILE definition binds as a VSAM access path, never as sequential")
        void everyCicsFileDefinitionIsAVsamAccessPath(String ddName) {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(DatasetBindings.class).binding(ddName).organization())
                    .as("%s organization", ddName)
                    .isIn("ksds", "aix-path"));
        }

        @Test
        @DisplayName("every entry declares a record length - the codec's single auditable width "
                + "source, so an entry without one would leave a repository unable to read")
        void everyEntryDeclaresARecordLength() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThat(bindings).allSatisfy((ddName, binding) ->
                        assertThat(binding.recordLength()).as("%s record length", ddName)
                                .isPositive());
            });
        }

        @Test
        @DisplayName("the record format is transcribed wherever the JCL declares a DCB, and the "
                + "report-date parameter dataset is the one entry whose JCL declares none")
        void recordFormatIsDeclaredExceptWhereTheJclDeclaresNoDcb() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> withoutARecordFormat = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.recordFormat() == null) {
                        withoutARecordFormat.add(ddName);
                    } else {
                        assertThat(binding.recordFormat()).as("%s record format", ddName)
                                .isIn("F", "FB");
                    }
                });
                // TRANREPT.jcl L73-L74 declares DISP=SHR and no DCB whatsoever for DATEPARM, so no
                // format is asserted for it; the geometry comes from CBTRN03C's own file
                // description instead. Every other entry's JCL supplies one.
                assertThat(withoutARecordFormat).containsExactly("DATEPARM");
            });
        }

        @Test
        @DisplayName("iteration order is deterministic across independent context loads, so "
                + "iteration and diagnostics never vary between runs of the same build")
        void iterationOrderIsDeterministicAcrossLoads() {
            List<String> firstLoad = new ArrayList<>();
            List<String> secondLoad = new ArrayList<>();
            shippedDefaultProfile().run(context -> firstLoad.addAll(
                    context.getBean(DatasetBindings.class).keySet()));
            shippedDefaultProfile().run(context -> secondLoad.addAll(
                    context.getBean(DatasetBindings.class).keySet()));
            assertThat(firstLoad).hasSize(27).isEqualTo(secondLoad);
        }
    }

    @Nested
    @DisplayName("Record geometry - copybook-fixed widths and JCL DCB attributes, verbatim")
    class RecordGeometry {

        /**
         * The complete geometry table: one row per configured DD name, twenty-seven rows.
         *
         * <p>Each width traces to a copybook and is corroborated independently by
         * {@code README.md} L67-L80, which tabulates the same widths against the same
         * {@code app/cpy} members. The four rows with no copybook are the outputs whose layout the
         * program declares inline rather than in a copybook: the reject file (the posting program
         * declares a 350-byte rejected record followed by an 80-byte validation trailer, which is
         * exactly 430), the report-date parameter dataset, and the two statement outputs.
         *
         * <p>The two {@code F} rows rather than {@code FB} are the interest job's generated
         * transaction output ({@code INTCALC.jcl} L39 {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)}) and
         * the posting job's reject file ({@code POSTTRAN.jcl} L36
         * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}). The one row with no record format is the
         * report-date parameter dataset, whose {@code DD} statement declares {@code DISP=SHR} and no
         * {@code DCB} at all.
         */
        @ParameterizedTest(name = "[{index}] {0} -> {1} bytes, RECFM {2}, copybook {3}")
        @CsvSource(nullValues = "-", value = {
            "ACCTDAT,  300, FB, CVACT01Y",
            "ACCTFILE, 300, FB, CVACT01Y",
            "CARDDAT,  150, FB, CVACT02Y",
            "CARDFILE, 150, FB, CVACT02Y",
            "CARDAIX,  150, FB, CVACT02Y",
            "CCXREF,    50, FB, CVACT03Y",
            "CARDXREF,  50, FB, CVACT03Y",
            "XREFFILE,  50, FB, CVACT03Y",
            "CXACAIX,   50, FB, CVACT03Y",
            "XREFFIL1,  50, FB, CVACT03Y",
            "CUSTDAT,  500, FB, CVCUS01Y",
            "CUSTFILE, 500, FB, CVCUS01Y",
            "TRANSACT, 350, FB, CVTRA05Y",
            "TRANFILE, 350, FB, CVTRA05Y",
            "SYSTRAN,  350, F,  CVTRA05Y",
            "USRSEC,    80, FB, CSUSR01Y",
            "DALYTRAN, 350, FB, CVTRA06Y",
            "DALYREJS, 430, F,  -",
            "TCATBALF,  50, FB, CVTRA01Y",
            "DISCGRP,   50, FB, CVTRA02Y",
            "TRANTYPE,  60, FB, CVTRA03Y",
            "TRANCATG,  60, FB, CVTRA04Y",
            "DATEPARM,  80, -,  -",
            "TRNXFILE, 350, FB, COSTM01",
            "TRANREPT, 133, FB, CVTRA07Y",
            "STMTFILE,  80, FB, -",
            "HTMLFILE, 100, FB, -",
        })
        @DisplayName("copybook-fixed width, JCL record format and copybook member")
        void copybookFixedGeometry(String ddName, int recordLength, String recordFormat,
                String copybook) {
            shippedDefaultProfile().run(context -> {
                DatasetBinding binding = context.getBean(DatasetBindings.class).binding(ddName);
                assertThat(binding.recordLength()).as("%s record length", ddName)
                        .isEqualTo(recordLength);
                assertThat(binding.recordFormat()).as("%s record format", ddName)
                        .isEqualTo(recordFormat);
                assertThat(binding.copybook()).as("%s copybook", ddName).isEqualTo(copybook);
            });
        }

        /**
         * Block sizes are transcribed from the JCL {@code DCB} where one is declared, and
         * {@code BLKSIZE=0} is <em>meaningful</em>: it asks for a system-determined block size, so
         * "declared as zero" must stay distinguishable from "not declared". That is why the
         * descriptor boxes this component, and why zero is asserted here rather than treated as
         * absent.
         */
        @ParameterizedTest(name = "[{index}] {0} -> BLKSIZE {1}")
        @CsvSource({
            "DALYREJS, 0",
            "TRANREPT, 0",
            "SYSTRAN,  0",
            "STMTFILE, 8000",
            "HTMLFILE, 800",
        })
        @DisplayName("the JCL DCB block size is transcribed, and a declared zero stays a declared "
                + "zero rather than becoming an absence")
        void declaredBlockSizesAreTranscribedIncludingZero(String ddName, int blockSize) {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(DatasetBindings.class).binding(ddName).blockSize())
                    .as("%s block size", ddName)
                    .isEqualTo(blockSize));
        }

        @Test
        @DisplayName("a block size is present for exactly the five entries whose JCL declares one, "
                + "and absent everywhere else")
        void blockSizeIsAbsentWhereNoDcbDeclaresOne() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> declared = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.blockSize() != null) {
                        declared.add(ddName);
                    }
                });
                assertThat(declared)
                        .containsExactlyInAnyOrderElementsOf(KEYS_WITH_A_DECLARED_BLOCK_SIZE);
            });
        }

        /**
         * Every keyed dataset states its key width, and every width is transcribed from a source file.
         *
         * <p>Only {@code TCATBALF} and {@code TRNXFILE} used to, on the reasoning that a width should
         * be stated only where a source file verifies it. That reasoning does not survive contact with
         * the copybooks: each of these widths is the {@code PICTURE} of the key field in the copybook
         * the entry already names, which is as verifiable as it gets. The effect of leaving them out
         * was that a keyed read had no declared authority for where its key ended.
         *
         * <p>The composite keys are the ones worth reading twice. {@code TCATBALF} is
         * {@code TRANCAT-ACCT-ID 9(11)} + {@code TRANCAT-TYPE-CD X(02)} + {@code TRANCAT-CD 9(04)} =
         * 17; {@code DISCGRP} is {@code DIS-ACCT-GROUP-ID X(10)} + {@code DIS-TRAN-TYPE-CD X(02)} +
         * {@code DIS-TRAN-CAT-CD 9(04)} = 16; {@code TRANCATG} is {@code TRAN-TYPE-CD X(02)} +
         * {@code TRAN-CAT-CD 9(04)} = 6; and {@code TRNXFILE}'s 32 is corroborated independently by
         * the {@code IDCAMS DEFINE CLUSTER} at {@code CREASTMT.JCL} L29-L39, which declares
         * {@code KEYS(32 0)} alongside {@code RECORDSIZE(350 350)}.
         */
        @ParameterizedTest(name = "[{index}] {0} -> {1}-byte key")
        @CsvSource({
            "ACCTDAT,  11",
            "CARDDAT,  16",
            "CARDAIX,  11",
            "CCXREF,   16",
            "CXACAIX,  11",
            "CUSTDAT,   9",
            "TRANSACT, 16",
            "USRSEC,    8",
            "ACCTFILE, 11",
            "CARDFILE, 16",
            "XREFFILE, 16",
            "XREFFIL1, 11",
            "CARDXREF, 16",
            "CUSTFILE,  9",
            "TRANFILE, 16",
            "DISCGRP,  16",
            "TCATBALF, 17",
            "TRANTYPE,  2",
            "TRANCATG,  6",
            "TRNXFILE, 32",
        })
        @DisplayName("every keyed dataset states the key width its copybook declares")
        void verifiableKeyWidthsAreStated(String ddName, int keyLength) {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(DatasetBindings.class).binding(ddName).keyLength())
                    .as("%s key length", ddName)
                    .isEqualTo(keyLength));
        }

        @Test
        @DisplayName("a key width is declared for every keyed entry and for no sequential one")
        void aKeyWidthIsDeclaredForEveryKeyedEntryAndNoOther() {
            // The two halves of one property. A keyed dataset with no key width has no authority for
            // where its key ends; a sequential dataset with one advertises an access path it does not
            // have. DatasetBindings.validate() enforces both at startup, and this asserts the shipped
            // configuration satisfies them.
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> keyedWithoutWidth = new ArrayList<>();
                List<String> sequentialWithWidth = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.keyed() && binding.keyLength() == null) {
                        keyedWithoutWidth.add(ddName);
                    }
                    if (!binding.keyed() && binding.keyLength() != null) {
                        sequentialWithWidth.add(ddName);
                    }
                });
                assertThat(keyedWithoutWidth).as("keyed entries missing a key width").isEmpty();
                assertThat(sequentialWithWidth).as("sequential entries claiming a key").isEmpty();
            });
        }

        @Test
        @DisplayName("the alternate-index paths declare the offset their key actually begins at")
        void theAlternateIndexPathsDeclareTheirKeyOffset() {
            // The one place an offset is not zero, and the reason keyOffset exists at all. CARDAIX's
            // alternate key CARD-ACCT-ID follows CARD-NUM X(16), so it begins at 16; CXACAIX's
            // XREF-ACCT-ID follows X(16) + 9(09), so it begins at 25. A primary key sits at 0, which
            // is what an absent offset means.
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThat(bindings.binding("CARDAIX").keyOffsetOrZero()).isEqualTo(16);
                assertThat(bindings.binding("CXACAIX").keyOffsetOrZero()).isEqualTo(25);
                assertThat(bindings.binding("XREFFIL1").keyOffsetOrZero()).isEqualTo(25);
                assertThat(bindings.binding("ACCTDAT").keyOffsetOrZero())
                        .as("a primary key begins at the start of the record")
                        .isZero();
                assertThat(bindings.binding("ACCTDAT").keyOffset())
                        .as("and declares no offset at all, rather than an explicit zero")
                        .isNull();
            });
        }

        @Test
        @DisplayName("every declared key span lies inside its record")
        void everyKeySpanLiesInsideItsRecord() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                bindings.forEach((ddName, binding) -> {
                    if (binding.keyed()) {
                        assertThat(binding.keyOffsetOrZero() + binding.keyLength())
                                .as("%s key ends within its %d-byte record", ddName,
                                        binding.recordLength())
                                .isLessThanOrEqualTo(binding.recordLength());
                    }
                });
            });
        }

        /**
         * The one genuine width conflict in the legacy sources, resolved and pinned.
         *
         * <p>{@code CREASTMT.JCL} declares the HTML statement output twice. L67-L71, the
         * {@code IEFBR14} step that pre-deletes the previous run's file, says
         * {@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)}. L92-L96, the step that actually creates it,
         * says {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}. The creating step wins. Do not average
         * the two, and do not take the first: the text statement output really is 80 bytes with an
         * 8000-byte block, and the resemblance is exactly what makes this easy to get wrong, which is
         * why both are asserted side by side here.
         */
        @Test
        @DisplayName("the HTML statement width comes from the creating step - 100 bytes and an "
                + "800-byte block, not the pre-delete step's 80 and 3200")
        void htmlStatementWidthComesFromTheCreatingStep() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                DatasetBinding html = bindings.binding("HTMLFILE");
                assertThat(html.recordLength()).isEqualTo(100).isNotEqualTo(80);
                assertThat(html.blockSize()).isEqualTo(800).isNotEqualTo(3200);
                DatasetBinding text = bindings.binding("STMTFILE");
                assertThat(text.recordLength()).isEqualTo(80);
                assertThat(text.blockSize()).isEqualTo(8000);
            });
        }

        @Test
        @DisplayName("the three relative-generation outputs are flagged as such, and nothing else is")
        void relativeGenerationOutputsAreFlagged() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> generations = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.gdg()) {
                        generations.add(ddName);
                    }
                });
                assertThat(generations)
                        .containsExactlyInAnyOrderElementsOf(GENERATION_DATA_GROUP_KEYS);
            });
        }
    }

    /**
     * An alternate index is an access <em>path</em> over a base cluster - not a second table, not a
     * second repository, and not separate data.
     *
     * <p>{@code app/jcl/INTCALC.jcl} proves it mechanically inside a single job step. L22 is
     * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}; within that one step, L29-L30 open the
     * cross reference as {@code XREFFILE} on the base {@code .VSAM.KSDS} while L31-L32 open it as
     * {@code XREFFIL1} on the {@code .VSAM.AIX.PATH} over that very same base. One dataset, two
     * access paths, one step. {@code app/catlg/LISTCAT.txt} corroborates from the catalogue side: the
     * card base cluster reports {@code KEYLEN 16 / RKP 0} with a 150-byte record and its alternate
     * index path reports {@code KEYLEN 11 / RKP 5} with the <em>same</em> 150-byte record, while the
     * alternate index's own index component reports a 505-byte maximum at a 512-byte control interval
     * - an index, not data. The cross reference mirrors it exactly at 50 bytes.
     *
     * <p>So the Java side gets one repository per base dataset and an additional alternate-key finder
     * method on it. These assertions are what stop that collapsing in either direction: aliases must
     * stay pointed at one dataset, and a path must stay distinguishable from its base.
     */
    @Nested
    @DisplayName("Aliases and alternate indexes - one dataset under several DD names, and a path is "
            + "not a second table")
    class AliasAndAlternateIndexEquivalence {

        @Test
        @DisplayName("the cross-reference base is one dataset reached under three DD names")
        void crossReferenceBaseIsOneDatasetUnderThreeDdNames() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                String base = bindings.binding("CCXREF").dsname();
                // READXREF.jcl L25-L26 and INTCALC.jcl L29-L30 name it XREFFILE; TRANREPT.jcl
                // L67-L68 names it CARDXREF; the CSD L37-L39 names it CCXREF.
                assertThat(bindings.binding("XREFFILE").dsname()).isEqualTo(base);
                assertThat(bindings.binding("CARDXREF").dsname()).isEqualTo(base);
            });
        }

        @Test
        @DisplayName("the cross-reference alternate index is one access path under two DD names, and "
                + "stays distinguishable from its base")
        void crossReferenceAlternateIndexIsOneAccessPathUnderTwoDdNames() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                String path = bindings.binding("CXACAIX").dsname();
                // INTCALC.jcl L31-L32 opens the same path as XREFFIL1 in the same step that opens
                // the base as XREFFILE, which is why both DD names exist.
                assertThat(bindings.binding("XREFFIL1").dsname()).isEqualTo(path);
                assertThat(path).isNotEqualTo(bindings.binding("CCXREF").dsname());
            });
        }

        @Test
        @DisplayName("the card alternate index is a path over the card base: a different location, "
                + "the same record")
        void cardAlternateIndexIsAPathOverTheCardBase() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                DatasetBinding path = bindings.binding("CARDAIX");
                DatasetBinding base = bindings.binding("CARDDAT");
                assertThat(path.dsname()).isNotEqualTo(base.dsname());
                assertThat(path.base()).isEqualTo("CARDDAT");
                assertThat(path.recordLength()).isEqualTo(base.recordLength());
                assertThat(path.copybook()).isEqualTo(base.copybook());
                assertThat(path.organization()).isEqualTo("aix-path");
                assertThat(base.organization()).isEqualTo("ksds");
            });
        }

        @ParameterizedTest(name = "[{index}] {0} is the batch DD name for {1}")
        @CsvSource({
            "ACCTFILE, ACCTDAT",
            "CARDFILE, CARDDAT",
            "CUSTFILE, CUSTDAT",
            "XREFFILE, CCXREF",
            "CARDXREF, CCXREF",
            "TRANFILE, TRANSACT",
            "XREFFIL1, CXACAIX",
        })
        @DisplayName("each batch DD alias resolves to the same dataset as its CICS FILE definition")
        void batchAliasesResolveToTheirCicsFileDefinition(String alias, String cicsFile) {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThat(bindings.binding(alias).dsname())
                        .as("%s and %s must name one dataset", alias, cicsFile)
                        .isEqualTo(bindings.binding(cicsFile).dsname());
            });
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = { "CARDAIX", "CXACAIX", "XREFFIL1" })
        @DisplayName("every alternate-index path names a configured base, shares that base's record "
                + "geometry, and declares the field forming its alternate key")
        void everyAlternateIndexPathNamesItsBaseAndSharesItsGeometry(String ddName) {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                DatasetBinding path = bindings.binding(ddName);
                assertThat(path.organization()).as("%s organization", ddName).isEqualTo("aix-path");
                assertThat(path.base()).as("%s base", ddName).isNotNull();
                assertThat(path.alternateKey()).as("%s alternate key", ddName).isNotBlank();
                // The base must itself be a configured entry - binding() throws if it is not, which
                // is the assertion: a path that named an unconfigured base would be unresolvable.
                DatasetBinding base = bindings.binding(path.base());
                assertThat(base.base()).as("%s base must be a base cluster, not another path",
                        path.base()).isNull();
                assertThat(path.recordLength()).as("%s record length", ddName)
                        .isEqualTo(base.recordLength());
                assertThat(path.copybook()).as("%s copybook", ddName).isEqualTo(base.copybook());
                assertThat(path.recordFormat()).as("%s record format", ddName)
                        .isEqualTo(base.recordFormat());
            });
        }

        @Test
        @DisplayName("a base is declared by exactly the three alternate-index paths and by nothing "
                + "else, so no base cluster can be mistaken for a path")
        void onlyAlternateIndexPathsDeclareABase() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> withABase = new ArrayList<>();
                List<String> declaredAsAPath = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.base() != null) {
                        withABase.add(ddName);
                    }
                    if ("aix-path".equals(binding.organization())) {
                        declaredAsAPath.add(ddName);
                    }
                });
                assertThat(withABase)
                        .containsExactlyInAnyOrderElementsOf(ALTERNATE_INDEX_PATH_KEYS);
                assertThat(declaredAsAPath)
                        .containsExactlyInAnyOrderElementsOf(ALTERNATE_INDEX_PATH_KEYS);
            });
        }

        /**
         * Under the fixture-backed {@code test} profile the alternate-index entries resolve to the
         * <em>same</em> fixture as their base, and that is not a flaw in the profile - it is the
         * clearest statement available of what an alternate index is. The bytes are identical; only
         * the key by which they are reached differs, exactly as the catalogue listing shows with its
         * matching record widths and differing key lengths. The default profile keeps the two
         * locations distinct because the mainframe addresses them under distinct dataset names, and
         * both facts are asserted so that neither can be "corrected" into the other.
         */
        @Test
        @DisplayName("the fixture profile resolves the cross-reference base and its alternate index "
                + "to one fixture - same data, different key")
        void fixtureProfileResolvesAnAlternateIndexOntoItsBaseFixture() {
            shippedTestProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                String fixture = bindings.binding("CCXREF").dsname();
                assertThat(bindings.binding("XREFFILE").dsname()).isEqualTo(fixture);
                assertThat(bindings.binding("CARDXREF").dsname()).isEqualTo(fixture);
                assertThat(bindings.binding("CXACAIX").dsname()).isEqualTo(fixture);
                assertThat(bindings.binding("XREFFIL1").dsname()).isEqualTo(fixture);
                // The geometry is untouched by the profile: 50 bytes including the trailing FILLER
                // the fixture itself omits, which the parity harness right-pads before comparison.
                assertThat(bindings.binding("CXACAIX").recordLength()).isEqualTo(50);
                assertThat(bindings.binding("CXACAIX").base()).isEqualTo("CCXREF");
            });
        }
    }

    /**
     * The keyed lookup, from both sides.
     *
     * <p>A DD name that is not configured is a configuration defect, and the lookup reports it as one
     * at the point of use rather than returning {@code null} and letting a repository read or write
     * the wrong dataset - or nothing at all - some frames later. Matching is exact: no
     * case-insensitive comparison, no trimming, no alias resolution and no default. That strictness
     * is deliberate and is asserted here, because "helpfully" relaxing it is exactly how a job would
     * come to open a dataset nobody intended.
     *
     * <p>Most of this group needs no application context at all, which is not a shortcut but the
     * property the production class documents about itself: the catalogue is a plain map with one
     * method, so both outcomes of its single decision are reachable by direct call. The
     * fixtures below use obviously synthetic locations, never a real dataset name.
     */
    @Nested
    @DisplayName("The keyed lookup - resolves what is configured, fails fast and audibly on what is "
            + "not")
    class FailFastDatasetLookup {

        private static final String SAMPLE_KEY = "ACCTDAT";

        private static final DatasetBinding SAMPLE_BINDING = new DatasetBinding(
                "SENTINEL.LOOKUP.SAMPLE", "ksds", false, "FB", null, 300, "CVACT01Y", null, null, null,
                null);

        private DatasetBindings catalogueWithOneEntry() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put(SAMPLE_KEY, SAMPLE_BINDING);
            return bindings;
        }

        @Test
        @DisplayName("every configured DD name resolves to exactly the descriptor that was bound "
                + "under it")
        void everyConfiguredKeyResolvesToItsBoundDescriptor() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                for (String ddName : ALL_DATASET_KEYS) {
                    assertThat(bindings.binding(ddName)).as("%s", ddName)
                            .isSameAs(bindings.get(ddName));
                }
            });
        }

        @Test
        @DisplayName("an unconfigured DD name fails fast, naming the offending key so the fix is "
                + "unambiguous")
        void unconfiguredDdNameFailsFastNamingTheOffendingKey() {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding("NOSUCHDD"))
                    .withMessageContaining("NOSUCHDD");
        }

        @Test
        @DisplayName("the diagnostic also names the configuration prefix and lists the keys that are "
                + "configured, so an operator never has to guess the spelling")
        void theDiagnosticNamesThePrefixAndTheConfiguredKeys() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThatIllegalStateException()
                        .isThrownBy(() -> bindings.binding("NOSUCHDD"))
                        .withMessageContaining("NOSUCHDD")
                        .withMessageContaining("carddemo.datasets")
                        .withMessageContaining("ACCTDAT")
                        .withMessageContaining("SYSTRAN");
            });
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = { "acctdat", "AcctDat", "acctDat", "ACCTDAt" })
        @DisplayName("matching is case-sensitive: a differently-cased DD name is not the same DD name")
        void lookupIsCaseSensitive(String differentlyCasedKey) {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThat(bindings).containsKey(SAMPLE_KEY);
            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding(differentlyCasedKey))
                    .withMessageContaining(differentlyCasedKey);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = { " ACCTDAT", "ACCTDAT ", " ACCTDAT " })
        @DisplayName("surrounding whitespace is not trimmed away: a padded DD name is not the same "
                + "DD name either")
        void surroundingWhitespaceIsNotTrimmed(String paddedKey) {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThatIllegalStateException().isThrownBy(() -> bindings.binding(paddedKey));
        }

        @Test
        @DisplayName("a null DD name is rejected exactly like any other unconfigured name, with a "
                + "diagnostic rather than a null-pointer failure")
        void nullDdNameIsRejectedLikeAnyOtherUnconfiguredName() {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding(null))
                    .withMessageContaining("null");
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = { "", " ", "   " })
        @DisplayName("an empty or blank DD name is rejected, not treated as a wildcard or a default")
        void blankDdNameIsRejected(String blankKey) {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThatIllegalStateException().isThrownBy(() -> bindings.binding(blankKey));
        }

        @Test
        @DisplayName("an empty catalogue still fails fast rather than handing back a null descriptor")
        void anEmptyCatalogueStillFailsFast() {
            DatasetBindings bindings = new DatasetBindings();
            assertThat(bindings).isEmpty();
            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding(SAMPLE_KEY))
                    .withMessageContaining(SAMPLE_KEY);
        }

        @Test
        @DisplayName("the catalogue is usable with no application context in the picture, and "
                + "iterates in a reproducible order")
        void theCatalogueNeedsNoApplicationContext() {
            DatasetBindings bindings = catalogueWithOneEntry();
            bindings.put("CARDDAT", withLocation(SAMPLE_BINDING, "SENTINEL.LOOKUP.SECOND"));
            assertThat(bindings.binding(SAMPLE_KEY)).isSameAs(SAMPLE_BINDING);
            assertThat(bindings.binding("CARDDAT").dsname()).isEqualTo("SENTINEL.LOOKUP.SECOND");
            // LinkedHashMap rather than HashMap is what makes iteration reproducible for a given
            // configuration; nothing in the module may depend on which order that is.
            assertThat(bindings).isInstanceOf(LinkedHashMap.class);
            assertThat(bindings.keySet()).containsExactly(SAMPLE_KEY, "CARDDAT");
        }
    }

    /**
     * One pool, one template over it, and a driver that arrives from configuration.
     *
     * <p>Two beans and one refusal. The pool is assembled entirely from {@code spring.datasource.*}
     * and {@code spring.datasource.hikari.*}: no size, timeout or statement-cache value is set in
     * Java, because this migration has no performance objective and enlarging a pool or introducing
     * parallelism would in fact threaten parity - several jobs depend on strict record ordering. The
     * template is constructed plain, with no fetch size, row limit or query timeout, for the same
     * reason: a template that silently capped rows would change observable behaviour rather than
     * preserve it.
     *
     * <p>The refusal is the interesting part. The shipped default profile spells the URL as an
     * environment placeholder with an empty default, so an unconfigured deployment yields an empty
     * string rather than a missing key - and the configuration refuses to start rather than falling
     * back to an embedded database. For a migration judged on byte-level parity, an application that
     * silently came up against the wrong backend would produce parity results that mean nothing,
     * which is a far worse outcome than not starting. Both spellings of "not configured" - absent,
     * and present but blank - are asserted to behave identically.
     *
     * <p>The driver is exercised the only way this environment allows: the test-scope in-memory
     * database. That is also what makes the driver assertion meaningful. Only a URL is supplied; the
     * driver class is never named in Java, and the context can therefore start only because the
     * driver is inferred from that URL. The site's mainframe data-access driver is a deployment-time
     * input, and production connectivity is unreachable from this build - stated here rather than
     * disguised by an invented driver class.
     */
    /**
     * The published {@link JdbcTemplate} is <strong>untuned</strong>, and no {@code carddemo.jdbc}
     * key exists for a deployment to tune it with (N-02).
     *
     * <p>Two properties are asserted, and they are different in kind. That the template is untuned is
     * an AAP requirement: 0.8.6 states that no connection tuning is introduced by this migration and
     * 0.4.2 specifies this bean as a plain {@code JdbcTemplate} over HikariCP. That <em>no key is
     * read</em> is the stronger of the two, because a key still bound in Java would let a deployment
     * reintroduce statement cancellation without any Java change - so the absence is asserted rather
     * than assumed, by starting a context that supplies nothing but a URL and a record-image form.
     *
     * <p>Where a site's bounds do belong is asserted too: driver-level timeouts reach the driver
     * untouched through {@code spring.datasource.hikari.data-source-properties.*}, which is the route
     * {@link DataSourceConfig#jdbcTemplate(DataSource)} documents. A documented route that did not
     * work would be worse than none.
     */
    @Nested
    @DisplayName("The template - untuned, with every driver-level bound supplied as configuration")
    class UntunedTemplate {

        /**
         * What an untouched {@code JdbcTemplate} reports, so each assertion below compares against the
         * framework's own default rather than against a number transcribed by hand.
         */
        private final JdbcTemplate untouched = new JdbcTemplate();

        @Test
        @DisplayName("nothing is configured on the published template: no statement bound, no fetch "
                + "size, no maximum row count")
        void nothingIsConfiguredOnThePublishedTemplate() {
            shippedDefaultProfile().run(context -> {
                JdbcTemplate template = context.getBean(JdbcTemplate.class);

                // -1 is JdbcTemplate's own "leave the driver's default alone". Setting a positive
                // bound would make a cancelled statement a data-access failure the COBOL has no arm
                // for, and row shaping would change what a program reads: a truncated browse is a
                // short file, and a short file is a different report.
                assertThat(template.getQueryTimeout()).isEqualTo(untouched.getQueryTimeout());
                assertThat(template.getFetchSize()).isEqualTo(untouched.getFetchSize());
                assertThat(template.getMaxRows()).isEqualTo(untouched.getMaxRows());
            });
        }

        @Test
        @DisplayName("the test profile's template is untuned in exactly the same way, so no profile "
                + "validates behaviour the other does not have")
        void theTestProfileTemplateIsUntunedTheSameWay() {
            shippedTestProfile().run(context -> {
                JdbcTemplate template = context.getBean(JdbcTemplate.class);

                assertThat(template.getQueryTimeout()).isEqualTo(untouched.getQueryTimeout());
                assertThat(template.getFetchSize()).isEqualTo(untouched.getFetchSize());
                assertThat(template.getMaxRows()).isEqualTo(untouched.getMaxRows());
            });
        }

        @Test
        @DisplayName("the context starts with no carddemo.jdbc key in the environment at all, which is "
                + "what proves none is read")
        void theContextStartsWithNoJdbcKeyAtAll() {
            // Deliberately NOT withInlinePropertiesOnly: this slice states the URL, the record-image
            // form and a valid catalogue and nothing else. If any carddemo.jdbc.* placeholder were
            // still bound in Java the refresh would fail on the unresolved placeholder.
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().containsProperty("carddemo.jdbc.query-timeout-seconds"))
                        .isFalse();
                assertThat(context.getBean(JdbcTemplate.class).getQueryTimeout())
                        .isEqualTo(untouched.getQueryTimeout());
            });
        }

        @Test
        @DisplayName("a stray carddemo.jdbc key in the environment changes nothing, because nothing "
                + "reads it")
        void aStrayJdbcKeyChangesNothing() {
            // A deployment that carries the removed key forward from an older configuration must not
            // quietly get statement cancellation back.
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "carddemo.jdbc.query-timeout-seconds=17")
                    .run(context -> assertThat(context.getBean(JdbcTemplate.class).getQueryTimeout())
                            .isEqualTo(untouched.getQueryTimeout()));
        }

        @Test
        @DisplayName("driver-level bounds reach the driver through configuration, so no timeout is "
                + "named in Java either")
        void driverLevelBoundsReachTheDriverThroughConfiguration() {
            // The login, connect and socket-read timeouts are spelled differently by every driver and
            // this module pins no driver coordinate (R-E). The javadoc on jdbcTemplate says they are
            // supplied under spring.datasource.hikari.data-source-properties.*, and this asserts that
            // claim rather than leaving it as prose.
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "spring.datasource.hikari.data-source-properties.socketTimeout=20000",
                    "spring.datasource.hikari.data-source-properties.loginTimeout=5")
                    .run(context -> {
                        HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);

                        assertThat(pool.getDataSourceProperties())
                                .containsEntry("socketTimeout", "20000")
                                .containsEntry("loginTimeout", "5");
                        // And they are handed to the driver untouched: no Java source reads, renames or
                        // validates them, because their names belong to the site's driver.
                        assertThat(context.getBean(JdbcTemplate.class).getQueryTimeout())
                                .isEqualTo(untouched.getQueryTimeout());
                    });
        }

        @Test
        @DisplayName("it is still the only JdbcOperations definition, with no @Primary anywhere")
        void thereIsStillExactlyOneTemplate() {
            shippedDefaultProfile().run(context -> {
                assertThat(context).hasSingleBean(JdbcTemplate.class);
                assertThat(context.getBeanNamesForType(JdbcOperations.class)).hasSize(1);
            });
        }
    }

    @Nested
    @DisplayName("The pool and the template - one of each, built from configuration, with the driver "
            + "supplied at deployment time")
    class DataSourceAndJdbcTemplateWiring {

        @Test
        @DisplayName("exactly one pooled DataSource is contributed, and it is the connection pool the "
                + "JDBC starter brings transitively")
        void exactlyOnePooledDataSourceIsContributed() {
            shippedDefaultProfile().run(context -> {
                assertThat(context).hasSingleBean(DataSource.class);
                assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
            });
        }

        @Test
        @DisplayName("the pool's settings are taken from configuration, not set in Java")
        void poolSettingsAreTakenFromConfiguration() {
            shippedDefaultProfile().run(context -> {
                HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                assertThat(pool.getPoolName()).isEqualTo("carddemo-pool");
                assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
                assertThat(pool.getMinimumIdle()).isEqualTo(2);
                assertThat(pool.getConnectionTimeout()).isEqualTo(30000L);
                // Commit boundaries belong to the batch step and the service layer, mirroring where
                // the COBOL performs its REWRITE, so the driver must not commit on its own.
                assertThat(pool.isAutoCommit()).isFalse();
            });
        }

        @Test
        @DisplayName("pool settings supplied inline round-trip onto the pool, which is what proves "
                + "they are bound rather than hard-coded")
        void poolSettingsSuppliedInlineRoundTrip() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "spring.datasource.hikari.pool-name=carddemo-inline-pool",
                    "spring.datasource.hikari.maximum-pool-size=3",
                    "spring.datasource.hikari.minimum-idle=1")
                    .run(context -> {
                        HikariDataSource pool =
                                (HikariDataSource) context.getBean(DataSource.class);
                        assertThat(pool.getPoolName()).isEqualTo("carddemo-inline-pool");
                        assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
                        assertThat(pool.getMinimumIdle()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("exactly one JdbcTemplate is contributed, over the very same pool instance - one "
                + "pool, not two")
        void exactlyOneJdbcTemplateOverTheSamePoolInstance() {
            shippedDefaultProfile().run(context -> {
                assertThat(context).hasSingleBean(JdbcTemplate.class);
                assertThat(context.getBean(JdbcTemplate.class).getDataSource())
                        .isSameAs(context.getBean(DataSource.class));
            });
        }

        @Test
        @DisplayName("the URL comes from configuration and the driver class is inferred from it, so "
                + "no driver coordinate is pinned in Java")
        void urlComesFromConfigurationAndTheDriverIsInferredFromIt() {
            // No driver class is supplied - only a URL - and the pool still ends up with the right
            // driver, which is what proves the driver is derived from the URL's scheme rather than
            // named in Java. The shipped default profile leaves spring.datasource.driver-class-name
            // empty for exactly this reason: a site that needs to name its own driver sets that
            // property, and nothing in this module presumes what it will be.
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY).run(context -> {
                HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                assertThat(pool.getJdbcUrl()).isEqualTo(
                        IN_MEMORY_URL_PROPERTY.substring("spring.datasource.url=".length()));
                assertThat(pool.getDriverClassName()).isEqualTo("org.h2.Driver");
            });
        }

        @Test
        @DisplayName("an explicitly configured driver class wins over the one the URL would imply")
        void anExplicitlyConfiguredDriverClassWins() {
            // The case that matters for a real deployment: a site-specific mainframe URL scheme is
            // not in any registry, so the driver has to be named. Proving the named value is the one
            // that reaches the pool - even where the URL scheme would have implied another - is what
            // makes that path trustworthy.
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "spring.datasource.driver-class-name=org.h2.jdbcx.JdbcDataSource")
                    .run(context -> {
                        HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                        assertThat(pool.getDriverClassName())
                                .isEqualTo("org.h2.jdbcx.JdbcDataSource");
                    });
        }

        @Test
        @DisplayName("a driver class that is not on the classpath refuses startup, naming the class "
                + "and the property that named it")
        void anAbsentDriverClassRefusesStartup() {
            // A pooled DataSource is lazy, so without this check the bean would be published, injected
            // into all twelve repositories, and fail on the first query - in the middle of a job.
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "spring.datasource.driver-class-name=com.example.NoSuchMainframeDriver")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("com.example.NoSuchMainframeDriver")
                                .hasMessageContaining("is not on the classpath")
                                .hasMessageContaining("spring.datasource.driver-class-name")
                                .hasMessageContaining("pins no JDBC driver coordinate by design")
                                // And it says how to fix it. A message that only states the driver is
                                // absent is unactionable for this artifact in particular: the jar is
                                // launched by PropertiesLauncher, so -cp beside -jar is discarded by the
                                // JVM and the obvious remedy silently does nothing.
                                .hasMessageContaining("LOADER_PATH")
                                .hasMessageContaining("loader.path")
                                .hasMessageContaining("-cp entry beside -jar is ignored");
                    });
        }

        @Test
        @DisplayName("an unrecognised URL scheme with no driver named refuses startup rather than "
                + "silently substituting the embedded database on the classpath")
        void anUndeterminableDriverRefusesStartupRatherThanFallingBackToH2() {
            // THE SUBSTANTIVE GUARD. Spring Boot's own driver determination ends by falling back to
            // whichever embedded database is on the classpath, and H2 is on this one at test scope to
            // back the Batch JobRepository and the parity harness. Without this refusal, a deployment
            // that mis-typed its driver property would be handed H2 and an empty in-memory database -
            // and every parity comparison would then be against nothing at all.
            withValidCatalogueAnd("spring.datasource.url=jdbc:carddemo-vsam://mainframe/PROD")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("No JDBC driver class could be determined")
                                .hasMessageContaining("spring.datasource.driver-class-name")
                                .hasMessageContaining("NO FALLBACK IS APPLIED")
                                .hasMessageContaining("embedded database on this classpath is never "
                                        + "substituted")
                                // This is the branch a site-specific URL scheme reaches first, so it
                                // carries the loading instruction too - the deployment is about to name
                                // a driver class and needs to know where the jar goes.
                                .hasMessageContaining("LOADER_PATH")
                                // The two halves are separated by a space. The first is a text block
                                // and the second is appended to it, and a text block strips trailing
                                // whitespace from every line - so a separator written INSIDE it would
                                // be removed and the two sentences would run together as
                                // "mean nothing.This module pins".
                                .hasMessageContaining("mean nothing. This module pins");
                    });
        }

        @Test
        @DisplayName("the pool is functional: a trivial query succeeds without any data definition "
                + "being issued first")
        void thePoolIsFunctionalWithoutAnyDataDefinition() {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(JdbcTemplate.class).queryForObject("SELECT 1", Integer.class))
                    .isEqualTo(1));
        }

        @Test
        @DisplayName("the shipped test profile supplies its own pool with no inline property at all, "
                + "and that pool is functional too")
        void theShippedTestProfileSuppliesItsOwnFunctionalPool() {
            shippedTestProfile().run(context -> {
                HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                // Named distinctly from the default profile's pool so a thread dump or a JMX view
                // makes it obvious at a glance which profile is live.
                assertThat(pool.getPoolName()).isEqualTo("carddemo-test-pool");
                assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
                assertThat(pool.getMinimumIdle()).isEqualTo(1);
                assertThat(pool.isAutoCommit()).isFalse();
                assertThat(context.getBean(JdbcTemplate.class)
                        .queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("with no URL configured at all the context refuses to start, naming the "
                + "properties an operator has to supply")
        void absentUrlRefusesToStartNamingTheMissingProperty() {
            withInlinePropertiesOnly().run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("spring.datasource.url")
                        .hasMessageContaining("spring.datasource.driver-class-name")
                        .hasMessageContaining("DEPLOYMENT-TIME INPUTS");
            });
        }

        @Test
        @DisplayName("the shipped default profile on its own cannot build a pool either: its URL is "
                + "an environment placeholder with an empty default, by design")
        void theShippedDefaultProfileAloneCannotBuildAPool() {
            // Built inline rather than through shippedDefaultProfile(), because the whole assertion is
            // that NO url is available - the helper supplies one. The profile key is stated for the
            // same reason the helper states it: under an inherited spring.profiles.active=test this
            // context would pick up the test profile's own url and start, and the assertion would
            // report a defect in the shipped document that is not there.
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(DataSourceConfig.class)
                    .withPropertyValues(NO_EXTERNALLY_ACTIVATED_PROFILE)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("spring.datasource.url");
                    });
        }

        /**
         * The default-profile slice reads the shipped default document whatever the surrounding
         * process says about profiles.
         *
         * <p>Every assertion in this file that names a default-profile value - the pool's name, the
         * mainframe dataset locations, the batch initialiser's {@code never}, the refusal to build a
         * pool without a URL - depends on {@link #shippedDefaultProfile()} loading
         * {@code application.yml} and nothing else. That dependency is invisible until something
         * activates a profile from outside the test, at which point those assertions quietly start
         * reading {@code application-test.yml} and reporting its values as the shipped document's.
         *
         * <p>Both routes by which that happens are reproduced here, because they are neutralised by
         * different precedence rules and a fix for one is not a fix for the other: a JVM system
         * property, as {@code mvn test -Dspring.profiles.active=test} supplies (set for the duration of
         * the run and restored afterwards), and a process environment variable, as a CI executor
         * exporting {@code SPRING_PROFILES_ACTIVE=test} supplies.
         */
        @Test
        @DisplayName("an externally activated profile cannot reach the default-profile slice, by "
                + "system property or by environment variable")
        void anExternallyActivatedProfileCannotReachTheDefaultSlice() {
            List<ApplicationContextRunner> underAnInheritedProfile = List.of(
                    shippedDefaultProfile().withSystemProperties("spring.profiles.active=test"),
                    shippedDefaultProfile(processEnvironmentActivating("test")));

            for (ApplicationContextRunner runner : underAnInheritedProfile) {
                runner.run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles())
                            .as("the slice must resolve no active profile at all")
                            .isEmpty();
                    assertThat(((HikariDataSource) context.getBean(DataSource.class)).getPoolName())
                            .as("the shipped default pool name, not the test profile's")
                            .isEqualTo("carddemo-pool");
                    assertThat(context.getBean(DatasetBindings.class).binding("ACCTDAT").dsname())
                            .as("the shipped default dataset location, not the fixture profile's")
                            .doesNotStartWith("CARDDEMO.TEST.");
                    assertThat(context.getEnvironment()
                            .getProperty("spring.batch.jdbc.initialize-schema"))
                            .isEqualTo("never");
                });
            }
        }

        @Test
        @DisplayName("an empty URL is treated exactly like an absent one")
        void anEmptyUrlIsTreatedLikeAnAbsentOne() {
            withInlinePropertiesOnly("spring.datasource.url=").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("spring.datasource.url");
            });
        }

        @Test
        @DisplayName("a whitespace-only URL is treated exactly like an absent one too, which is what "
                + "an unresolved environment placeholder actually produces")
        void aWhitespaceOnlyUrlIsTreatedLikeAnAbsentOne() {
            // Supplied through a property source rather than the runner's inline values, because
            // those are trimmed on the way in and a trimmed blank would no longer be a blank.
            new ApplicationContextRunner()
                    .withUserConfiguration(DataSourceConfig.class)
                    .withInitializer(context -> context.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("whitespaceOnlyUrl",
                                    Map.of("spring.datasource.url", "   "))))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("spring.datasource.url");
                    });
        }
    }

    /**
     * What the configuration refuses to contribute, asserted exhaustively.
     *
     * <p>These assertions are load-bearing rather than decorative. A suite that checked only that the
     * pool and the template exist would pass against an implementation that had also grown a second
     * transaction manager, a schema initialiser, an object-relational mapping or a generated index -
     * and every one of those would breach a stated constraint of this migration. So the bean set is
     * pinned by <strong>exhaustion</strong>: five beans, matched by type, nothing else permitted.
     * Exhaustiveness also means this group never has to enumerate the artefacts it forbids, which
     * matters practically - a prohibition notice that quoted them would register as a hit in the
     * negative source scans that police this module, cost every later reviewer an adjudication, and
     * give a genuine violation somewhere to hide.
     *
     * <p>The transaction manager is a special case worth stating plainly rather than by exhaustion.
     * Exactly one exists in this module and the batch configuration owns it. Spring Batch and Spring
     * JDBC both resolve one by type, so a second definition here would make that resolution ambiguous
     * and stop the context from starting at all.
     */
    @Nested
    @DisplayName("The negative contract - seven beans, no transaction manager, and nothing "
            + "schema-shaped")
    class NegativeContract {

        /**
         * The complete set of beans this configuration is allowed to contribute. Spring registers its
         * own infrastructure under bean names prefixed with its root package, so filtering on that
         * prefix leaves precisely the beans this class is answerable for.
         */
        private static final List<Class<?>> PERMITTED_BEAN_TYPES = List.of(
                DataSourceConfig.class, HikariDataSource.class, JdbcTemplate.class,
                DataSourceProperties.class, DatasetBindings.class, RecordImageForm.class,
                PhysicalSequence.class);

        @Test
        @DisplayName("the configuration contributes exactly seven beans, and each is one of the seven "
                + "it is answerable for")
        void theConfigurationContributesExactlySevenBeans() {
            shippedDefaultProfile().run(context -> {
                List<String> contributed = new ArrayList<>();
                for (String beanName : context.getBeanDefinitionNames()) {
                    if (!beanName.startsWith("org.springframework.")) {
                        contributed.add(beanName);
                    }
                }
                List<Class<?>> matched = new ArrayList<>();
                for (String beanName : contributed) {
                    Class<?> beanType = context.getBeanFactory().getType(beanName);
                    assertThat(beanType).as("type of bean '%s'", beanName).isNotNull();
                    // The configuration class itself is proxied, so assignability rather than
                    // equality is the right test for every entry.
                    PERMITTED_BEAN_TYPES.stream()
                            .filter(permitted -> permitted.isAssignableFrom(beanType))
                            .forEach(matched::add);
                }
                assertThat(matched).as("beans contributed: %s", contributed)
                        .containsExactlyInAnyOrderElementsOf(PERMITTED_BEAN_TYPES);
                assertThat(contributed).hasSameSizeAs(PERMITTED_BEAN_TYPES);
            });
        }

        @Test
        @DisplayName("the record-image representation is one bean, and it is the only one of its kind")
        void theRecordImageRepresentationIsTheOnlyOneOfItsKind() {
            // One authority means one bean. Two definitions would let two repositories be injected with
            // different representations of the same column, which is the divergence this bean exists to
            // end - so the count is asserted, not just the presence.
            shippedDefaultProfile().run(context -> {
                assertThat(context.getBeanNamesForType(RecordImageForm.class))
                        .containsExactly(RecordImageForm.FORM_BEAN_NAME);
                assertThat(context.getBean(RecordImageForm.class))
                        .isSameAs(RecordImageForm.CHARACTER);
            });
        }

        @Test
        @DisplayName("the physical-record ordinal is one bean too, for the same reason: two would let "
                + "two components order the same dataset differently")
        void thePhysicalOrdinalIsTheOnlyOneOfItsKind() {
            shippedDefaultProfile().run(context -> {
                assertThat(context.getBeanNamesForType(PhysicalSequence.class))
                        .containsExactly(PhysicalSequence.BEAN_NAME);
                assertThat(context.getBean(PhysicalSequence.class).expression())
                        .isEqualTo("RECORD_ORDINAL");
            });
        }

        @Test
        @DisplayName("the shipped test profile states the representation too, so neither profile "
                + "defaults it")
        void theTestProfileStatesTheRepresentationAsWell() {
            shippedTestProfile().run(context ->
                    assertThat(context.getBean(RecordImageForm.class))
                            .isSameAs(RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("with the representation unstated the context refuses to start, naming the key")
        void anUnstatedRepresentationRefusesStartup() {
            // The whole reason the key carries no default: a deployment that never said how its driver
            // presents a record image must not start and then hand every record to a conversion nobody
            // chose. Asserted against the no-document slice, because that is the only place where the
            // absence is reachable - both shipped profiles state it.
            new ApplicationContextRunner()
                    .withUserConfiguration(DataSourceConfig.class)
                    .withPropertyValues(Stream.concat(minimalValidCatalogue(),
                            Stream.of(IN_MEMORY_URL_PROPERTY)).toArray(String[]::new))
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "carddemo.record-image.form={0} is refused")
        @ValueSource(strings = { "CHAR", "BINARY_LARGE_OBJECT", "TEXT", "utf8", "1" })
        @DisplayName("a representation the module does not implement is refused by name, never "
                + "defaulted")
        void anUnknownRepresentationRefusesStartup(String configured) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    RecordImageForm.FORM_PROPERTY + "=" + configured)
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "carddemo.record-image.form={0} resolves")
        @ValueSource(strings = { "CHARACTER", "BINARY", "character", " binary ", "Character" })
        @DisplayName("both representations resolve, case-insensitively and whitespace-tolerantly")
        void bothRepresentationsResolve(String configured) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    RecordImageForm.FORM_PROPERTY + "=" + configured)
                    .run(context -> assertThat(context.getBean(RecordImageForm.class))
                            .isSameAs(RecordImageForm.parse(configured)));
        }

        @Test
        @DisplayName("no transaction manager is declared here - the batch configuration owns the only "
                + "one the module has")
        void noTransactionManagerIsDeclaredHere() {
            shippedDefaultProfile().run(context ->
                    assertThat(context).doesNotHaveBean(PlatformTransactionManager.class));
        }

        @Test
        @DisplayName("the default profile initialises nothing against the configured backend, and "
                + "creates no framework metadata there either")
        void theDefaultProfileInitialisesNothing() {
            shippedDefaultProfile().run(context -> {
                assertThat(context.getEnvironment().getProperty("spring.sql.init.mode"))
                        .isEqualTo("never");
                assertThat(context.getEnvironment()
                        .getProperty("spring.batch.jdbc.initialize-schema")).isEqualTo("never");
            });
        }

        /**
         * The descriptor's public surface, pinned by exhaustion: a location, and the geometry needed
         * to read and write a fixed-width record. Ten components, in declaration order, and no
         * eleventh - which is how this assertion evidences that the descriptor carries no
         * concurrency token and no generated access structure. The optimistic-concurrency check the
         * legacy programs already perform is reproduced in the update services, field for field,
         * exactly as the COBOL performs it; it is not, and must not become, a property of a dataset
         * binding.
         */
        @Test
        @DisplayName("the dataset descriptor exposes a location, a geometry and one cluster attribute - "
                + "exactly twelve components, and no thirteenth")
        void theDescriptorExposesOnlyLocationAndGeometry() {
            List<String> componentNames = Stream.of(DatasetBinding.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            // reusable is the twelfth and is a cluster ATTRIBUTE rather than a location or a geometry:
            // app/catlg/LISTCAT.txt records REUSE or NOREUSE per cluster, and it is what decides whether
            // an OPEN OUTPUT of an indexed file - VSAM load mode - can begin against a non-empty one.
            assertThat(componentNames).containsExactly("dsname", "organization", "gdg",
                    "recordFormat", "blockSize", "recordLength", "copybook", "keyLength",
                    "keyOffset", "base", "alternateKey", "reusable");
        }

        @Test
        @DisplayName("the descriptor is an immutable value with value-based equality, which is what "
                + "lets two profiles' geometry be compared directly")
        void theDescriptorIsAnImmutableValue() {
            assertThat(DatasetBinding.class.isRecord()).isTrue();
            assertThat(Modifier.isFinal(DatasetBinding.class.getModifiers())).isTrue();
            DatasetBinding one = new DatasetBinding("SENTINEL.VALUE.ONE", "ksds", false, "FB", null,
                    300, "CVACT01Y", null, null, null, null);
            DatasetBinding same = new DatasetBinding("SENTINEL.VALUE.ONE", "ksds", false, "FB", null,
                    300, "CVACT01Y", null, null, null, null);
            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same)
                    .isNotEqualTo(withLocation(one, "SENTINEL.VALUE.TWO"));
        }
    }

    /**
     * Every dataset name lives in configuration and is reached by DD-name key only.
     *
     * <p>The textual form of that requirement is a build-time scan of the source tree for a mainframe
     * dataset literal, and no assertion in a unit test can stand in for it. What this group does
     * instead is prove the same property <em>behaviourally</em>, and portably: supply a location
     * through configuration that could not plausibly be a default, and require it to come back out.
     * A name baked into Java would win, and the sentinel would not appear. The companion assertion is
     * the empty catalogue: with nothing configured, nothing is bound - so there is no hidden default
     * anywhere in the class.
     */
    /**
     * The physical-record ordinal: one configured name, no default, and a grammar that keeps an
     * operator's value from reaching an {@code ORDER BY} clause as anything but a name.
     *
     * <p>Why it matters enough to have its own set of assertions: a physical-sequential dataset has no
     * key, so its order is its records' position, and SQL returns rows in no order unless a statement
     * says which. A deployment that never named its ordinal must therefore not start - the alternative
     * is a report with the right rows and the wrong totals, produced silently.
     */
    @Nested
    @DisplayName("The physical-record ordinal - configured, required, and grammar-checked")
    class PhysicalRecordOrdinal {

        @Test
        @DisplayName("the shipped default profile states it, so the bean resolves without a default")
        void theShippedDefaultProfileStatesIt() {
            shippedDefaultProfile().run(context ->
                    assertThat(context.getBean(PhysicalSequence.class).expression())
                            .isEqualTo("RECORD_ORDINAL"));
        }

        @Test
        @DisplayName("the fixture-backed test profile states H2's own row identifier")
        void theTestProfileStatesTheRowIdentifier() {
            shippedTestProfile().run(context ->
                    assertThat(context.getBean(PhysicalSequence.class).expression())
                            .isEqualTo("_ROWID_"));
        }

        @Test
        @DisplayName("with the ordinal unstated the context refuses to start rather than reading a "
                + "physical-sequential dataset in whatever order the backend scanned")
        void anUnstatedOrdinalRefusesStartup() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DataSourceConfig.class)
                    .withPropertyValues(Stream.concat(minimalValidCatalogue(),
                                    Stream.of(IN_MEMORY_URL_PROPERTY, RECORD_IMAGE_FORM_PROPERTY))
                            .toArray(String[]::new))
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "carddemo.physical-sequence.expression={0} is refused")
        @ValueSource(strings = {
            "RRN DESC",
            "RRN;DROP TABLE X",
            "RRN,SEQ",
            "COUNT(*)",
            "1RRN",
            "'RRN'",
            "\"RRN\"",
        })
        @DisplayName("a value that is not a bare identifier is refused at startup, never rendered")
        void aNonIdentifierRefusesStartup(String configured) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    PhysicalSequence.EXPRESSION_PROPERTY + "=" + configured)
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "carddemo.physical-sequence.expression={0} resolves")
        @ValueSource(strings = { "_ROWID_", "RRN", "RECORD_ORDINAL", "seq9", "#POS", "@ORD", "$N" })
        @DisplayName("every shape a gateway ordinal legitimately takes resolves, whitespace-tolerantly")
        void everyLegitimateOrdinalResolves(String configured) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    PhysicalSequence.EXPRESSION_PROPERTY + "= " + configured + " ")
                    .run(context -> assertThat(context.getBean(PhysicalSequence.class).expression())
                            .isEqualTo(configured));
        }
    }

    @Nested
    @DisplayName("Dataset locations come from configuration - nothing is defaulted inside Java")
    class DatasetNameExternalisation {

        @ParameterizedTest(name = "[{index}] {0} -> {1}")
        @CsvSource({
            "ACCTDAT,  SENTINEL.EXTERNALISED.ACCTDAT",
            "CARDAIX,  SENTINEL.EXTERNALISED.CARDAIX",
            "CCXREF,   SENTINEL.EXTERNALISED.CCXREF",
            "XREFFIL1, SENTINEL.EXTERNALISED.XREFFIL1",
            "HTMLFILE, SENTINEL.EXTERNALISED.HTMLFILE",
        })
        @DisplayName("a location that could not be a default survives binding unchanged")
        void sentinelLocationsSurviveBindingUnchanged(String ddName, String sentinelLocation) {
            String prefix = "carddemo.datasets." + ddName + ".";
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY, prefix + "dsname=" + sentinelLocation)
                    .run(context -> {
                        DatasetBindings bindings = context.getBean(DatasetBindings.class);
                        assertThat(bindings.keySet())
                                .containsExactlyInAnyOrderElementsOf(ALL_DATASET_KEYS);
                        DatasetBinding binding = bindings.binding(ddName);
                        assertThat(binding.dsname()).isEqualTo(sentinelLocation);
                        assertThat(binding.recordLength()).isEqualTo(50);
                        assertThat(binding.recordFormat()).isEqualTo("FB");
                    });
        }

        @Test
        @DisplayName("an entirely unconfigured catalogue refuses startup, listing every DD name the "
                + "migrated code reads")
        void anUnconfiguredCatalogueRefusesStartup() {
            // This assertion replaces one that asserted the opposite - that an unconfigured catalogue
            // was a successfully started context with an empty map. That was the wrong contract, and
            // dangerously so: an empty catalogue means every repository in the module resolves nothing,
            // and the first symptom would have been a job failing to open a dataset at run time.
            //
            // The URL is supplied so the pool's own guard cannot be the thing that fails, which is what
            // makes the catalogue the subject here rather than a bystander.
            withInlinePropertiesOnly(IN_MEMORY_URL_PROPERTY).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must declare exactly the 27 DD names")
                        .hasMessageContaining("Missing: [ACCTDAT,")
                        .hasMessageContaining("Unexpected: []");
            });
        }

        @Test
        @DisplayName("one missing DD name refuses startup, naming exactly that one")
        void oneMissingDdNameRefusesStartup() {
            // A DD name is read by name somewhere in the migrated code, so a catalogue short of one is
            // a job that cannot resolve its own dataset. Reported at startup, naming the absentee.
            String[] withoutTcatbalf = minimalValidCatalogue()
                    .filter(property -> !property.startsWith("carddemo.datasets.TCATBALF."))
                    .toArray(String[]::new);
            withInlinePropertiesOnly(Stream.concat(
                            Stream.of(withoutTcatbalf), Stream.of(IN_MEMORY_URL_PROPERTY))
                            .toArray(String[]::new))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Missing: [TCATBALF]")
                                .hasMessageContaining("Unexpected: []");
                    });
        }

        @Test
        @DisplayName("an unexpected DD name refuses startup, and is reported alongside the name it "
                + "was probably a typo for")
        void anUnexpectedDdNameRefusesStartup() {
            // The two lists together are what make a typo obvious: the misspelling appears under
            // Unexpected and the name it displaced appears under Missing, in one message.
            String[] misspelled = minimalValidCatalogue()
                    .map(property -> property.replace("carddemo.datasets.TCATBALF.",
                            "carddemo.datasets.TCATBLAF."))
                    .toArray(String[]::new);
            withInlinePropertiesOnly(Stream.concat(
                            Stream.of(misspelled), Stream.of(IN_MEMORY_URL_PROPERTY))
                            .toArray(String[]::new))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Missing: [TCATBALF]")
                                .hasMessageContaining("Unexpected: [TCATBLAF]");
                    });
        }

        @Test
        @DisplayName("an unknown property on an otherwise valid entry refuses startup rather than "
                + "being silently discarded")
        void anUnknownPropertyOnAnEntryRefusesStartup() {
            // Binding is strict, and this is why. Discarded silently, a mis-spelled record-lenght
            // leaves the entry bound with the component absent and its type's default - zero - standing
            // in for the width, which is the single value most able to corrupt every record written.
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "carddemo.datasets.ACCTDAT.record-lenght=300")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .isInstanceOf(ConfigurationPropertiesBindException.class);
                    });
        }

        @ParameterizedTest(name = "[{index}] {0} is rejected: {1}")
        @CsvSource(delimiter = '|', value = {
            "carddemo.datasets.ACCTDAT.dsname=                 | it declares no dsname",
            "carddemo.datasets.ACCTDAT.organization=vsam       | its organization is 'vsam'",
            "carddemo.datasets.ACCTDAT.record-format=V         | its record-format is 'V'",
            "carddemo.datasets.ACCTDAT.record-length=0         | its record-length is 0",
            "carddemo.datasets.ACCTDAT.record-length=-1        | its record-length is -1",
            "carddemo.datasets.ACCTDAT.block-size=-1           | its block-size is -1",
            "carddemo.datasets.ACCTDAT.key-length=0            | its key-length is 0",
        })
        @DisplayName("an invalid component on any entry refuses startup, naming the DD name and the "
                + "component")
        void anInvalidComponentRefusesStartup(String override, String expectedDiagnostic) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY, override.trim()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("entry for DD name 'ACCTDAT' is invalid")
                        .hasMessageContaining(expectedDiagnostic.trim());
            });
        }

        @ParameterizedTest(name = "[{index}] {1}")
        @CsvSource(delimiter = '|', value = {
            "carddemo.datasets.ACCTDAT.base=CARDDAT         | names base 'CARDDAT'",
            "carddemo.datasets.CARDAIX.base=                | names no base",
            "carddemo.datasets.CARDAIX.alternate-key=       | names no alternate-key",
            "carddemo.datasets.CARDAIX.base=NOSUCHDD        | which is not itself a declared DD name",
            "carddemo.datasets.CARDAIX.base=CXACAIX        "
                    + "| which is itself an alternate-index path",
        })
        @DisplayName("an incoherent alternate-index relationship refuses startup (gate G45)")
        void anIncoherentAlternateIndexRelationshipRefusesStartup(
                String override, String expectedDiagnostic) {
            // Gate G45 enforced mechanically rather than by comment: a path names the base cluster it
            // indexes and the key it indexes on, a non-path names neither, and a base is a base rather
            // than a second path. Chaining paths would imply an index over an index, which no VSAM
            // definition in app/csd/CARDDEMO.CSD declares.
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY, override.trim()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("is invalid")
                        .hasMessageContaining(expectedDiagnostic.trim());
            });
        }

        /**
         * A profile may repoint a dataset and may change nothing else about it. The fixture-backed
         * {@code test} profile repoints all twenty-seven, and this assertion holds every other
         * component of every entry invariant across the two profiles - so a profile document can
         * never quietly relax a record width, which would break byte-level parity while leaving every
         * other test green.
         */
        @Test
        @DisplayName("a profile overrides where a dataset lives and never what shape its records are")
        void aProfileOverridesLocationButNeverGeometry() {
            Map<String, DatasetBinding> shipped = new LinkedHashMap<>();
            Map<String, DatasetBinding> underTestProfile = new LinkedHashMap<>();
            shippedDefaultProfile().run(context ->
                    shipped.putAll(context.getBean(DatasetBindings.class)));
            shippedTestProfile().run(context ->
                    underTestProfile.putAll(context.getBean(DatasetBindings.class)));

            assertThat(underTestProfile.keySet())
                    .containsExactlyInAnyOrderElementsOf(shipped.keySet());
            for (String ddName : ALL_DATASET_KEYS) {
                DatasetBinding shippedBinding = shipped.get(ddName);
                DatasetBinding testBinding = underTestProfile.get(ddName);
                assertThat(testBinding.dsname())
                        .as("%s must be repointed by the fixture profile", ddName)
                        .isNotEqualTo(shippedBinding.dsname());
                assertThat(withLocation(testBinding, shippedBinding.dsname()))
                        .as("%s geometry must survive the profile untouched", ddName)
                        .isEqualTo(shippedBinding);
            }
        }
    }
}
