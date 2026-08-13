package com.vsergeychik.carddemo.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.testsupport.FixtureInventory.FixtureDeclaration;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The {@code test} profile's fixture inventory: bound strictly, held to what it declares, and seeded.
 *
 * <h2>What was wrong, and what this suite is the evidence for</h2>
 * <p>{@code src/test/resources/carddemo-test-fixtures.yml} declared ten entries with measured geometry,
 * two normalisations and the DD names each one serves, and the profile imported it - but no
 * {@code @ConfigurationProperties}, {@code Binder} or {@code Environment} consumer read a single value of
 * it. The declaration was prose in YAML syntax: its resources could stop resolving, its record counts and
 * widths could drift from the files they describe, its two pads could go unapplied, and a JVM started on
 * the profile had twenty-seven dataset bindings with no relations behind any of them. Nothing would have
 * reported any of that.
 *
 * <p>So this suite asserts three separate things, and the third is the one the review asked for:
 * <ol>
 *   <li>the inventory <strong>binds</strong>, strictly, from the shipped {@code test} profile;</li>
 *   <li>every number it declares is <strong>re-derived</strong> from the fixture bytes and from the
 *       shipped dataset catalogue, and each guard refuses the case it exists for;</li>
 *   <li>{@link FixtureSeeder} <strong>materialises</strong> every declared relation, row and
 *       normalisation into the profile's own backend - so the relations a hand-started JVM reaches now
 *       exist and hold the declared data.</li>
 * </ol>
 *
 * <p>The suite constructs its own in-memory backend rather than sharing one, so nothing it seeds can
 * reach another test - which is the same property every parity case relies on.
 */
@DisplayName("carddemo.test.fixtures - bound strictly, re-measured, and actually seeded")
class FixtureInventoryTest {

    /** The ten dataset names the declared inventory resolves to under the {@code test} profile. */
    private static final List<String> SEEDED_DATASETS = List.of(
            "CARDDEMO.TEST.ACCTDATA.VSAM.KSDS",
            "CARDDEMO.TEST.CARDDATA.VSAM.KSDS",
            "CARDDEMO.TEST.CARDXREF.VSAM.KSDS",
            "CARDDEMO.TEST.CUSTDATA.VSAM.KSDS",
            "CARDDEMO.TEST.DALYTRAN",
            "CARDDEMO.TEST.DISCGRP",
            "CARDDEMO.TEST.TCATBAL.VSAM.KSDS",
            "CARDDEMO.TEST.TRANCATG",
            "CARDDEMO.TEST.TRANTYPE",
            "CARDDEMO.TEST.USRSEC.VSAM.KSDS");

    /** The code page the fixtures are stored in - the value the {@code test} profile configures. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** Resolves the declared {@code classpath:} fixture locations. */
    private static final ResourceLoader LOADER = new DefaultResourceLoader();

    /**
     * A slice over the shipped {@code test} profile with the inventory bound.
     *
     * @return a runner that yields both the dataset catalogue and the fixture inventory
     */
    private static ApplicationContextRunner boundInventory() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(CobolCharsetConfig.class, DataSourceConfig.class,
                        TransactionBoundary.class, FixtureSeedingConfiguration.class)
                .withPropertyValues("spring.profiles.active=test");
    }

    @Nested
    @DisplayName("The binding - ten entries, from the shipped test profile, strictly")
    class TheBinding {

        @Test
        @DisplayName("all ten declared entries bind, each with its geometry and its DD names")
        void allTenEntriesBind() {
            boundInventory().run(context -> {
                FixtureInventory inventory = context.getBean(FixtureInventory.class);

                assertThat(inventory.keySet())
                        .as("the nine fixtures derived from app/data/ASCII plus usrsec, which has no "
                                + "file anywhere in the repository")
                        .containsExactlyInAnyOrderElementsOf(FixtureInventory.REQUIRED_FIXTURES);
                assertThat(inventory.get("acctdata"))
                        .isEqualTo(new FixtureDeclaration("classpath:fixtures/acctdata.txt", 50, 300,
                                null, List.of("ACCTDAT", "ACCTFILE"), null));
                assertThat(inventory.get("cardxref").padTo())
                        .as("deviation 1 of 2: 36 measured, 50 declared by CVACT03Y")
                        .isEqualTo(50);
                assertThat(inventory.get("usrsec").seed())
                        .as("the ten in-stream records of app/jcl/DUSRSECJ.jcl")
                        .hasSize(10);
            });
        }

        @Test
        @DisplayName("exactly two entries declare a normalisation, so the deviation set is enumerable")
        void exactlyTwoEntriesDeclareANormalisation() {
            boundInventory().run(context -> {
                Map<String, FixtureDeclaration> declared =
                        context.getBean(FixtureInventory.class).declarations();

                assertThat(declared.entrySet())
                        .filteredOn(entry -> entry.getValue().padTo() != null)
                        .extracting(Map.Entry::getKey)
                        .as("cardxref omits FILLER X(14) and usrsec omits SEC-USR-FILLER X(23); every "
                                + "other fixture already matches its copybook exactly")
                        .containsExactlyInAnyOrder("cardxref", "usrsec");
            });
        }

        @Test
        @DisplayName("the shipped declaration validates against the shipped dataset catalogue")
        void theShippedDeclarationValidates() {
            boundInventory().run(context -> assertThatNoException().isThrownBy(() ->
                    context.getBean(FixtureInventory.class)
                            .validate(context.getBean(DatasetBindings.class))));
        }

        @Test
        @DisplayName("an unknown key under the prefix fails the bind rather than being discarded")
        void anUnknownKeyFailsTheBind() {
            // ignoreUnknownFields = false. A misspelled property under a strictly bound prefix is the
            // failure mode this setting exists for: bound loosely, "byte-per-record" would simply leave
            // the width at zero and the entry would describe nothing.
            new ApplicationContextRunner()
                    .withUserConfiguration(StrictBindingOnly.class)
                    .withPropertyValues(
                            FixtureInventory.PREFIX + ".acctdata.resource=classpath:fixtures/x.txt",
                            FixtureInventory.PREFIX + ".acctdata.byte-per-record=300")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Nested
    @DisplayName("The re-measurement - the declaration is a claim, and every number is checked")
    class TheReMeasurement {

        @Test
        @DisplayName("every declared fixture resolves and measures exactly what it declares")
        void everyFixtureMeasuresWhatItDeclares() {
            // The teeth. Nothing here trusts the declaration: each file is read and each line counted and
            // measured, so a fixture that gained a record or lost a byte fails on the fixture rather than
            // days later inside a parity diff.
            boundInventory().run(context -> {
                FixtureInventory inventory = context.getBean(FixtureInventory.class);
                inventory.forEach((name, declaration) -> {
                    List<String> records = inventory.recordsOf(declaration, LOADER, ASCII);
                    assertThat(records)
                            .as("%s declares %d records", name, declaration.records())
                            .hasSize(declaration.records())
                            .allSatisfy(record -> assertThat(record)
                                    .as("%s normalises every record to %d bytes", name,
                                            declaration.normalisedWidth())
                                    .hasSize(declaration.normalisedWidth()));
                });
            });
        }

        @Test
        @DisplayName("the two normalisations are applied at seed time, with spaces, once")
        void theTwoNormalisationsAreApplied() {
            boundInventory().run(context -> {
                FixtureInventory inventory = context.getBean(FixtureInventory.class);

                List<String> xref = inventory.recordsOf(inventory.get("cardxref"), LOADER, ASCII);
                assertThat(xref).allSatisfy(record -> {
                    assertThat(record).hasSize(50);
                    assertThat(record.substring(36))
                            .as("the restored FILLER X(14) is spaces, not zeros and not nulls")
                            .isEqualTo(" ".repeat(14));
                    assertThat(record.substring(0, 36).stripTrailing())
                            .as("the 36 measured bytes are untouched")
                            .isNotEmpty();
                });

                List<String> users = inventory.recordsOf(inventory.get("usrsec"), LOADER, ASCII);
                assertThat(users).hasSize(10).allSatisfy(record -> {
                    assertThat(record).hasSize(80);
                    assertThat(record.substring(57)).isEqualTo(" ".repeat(23));
                });
                assertThat(users.get(0)).startsWith("ADMIN001");
                assertThat(users.get(0).substring(48, 56))
                        .as("SEC-USR-PWD PIC X(08), plaintext exactly as COSGN00C compares it")
                        .isEqualTo("PASSWORD");
                assertThat(users.get(0).charAt(56)).isEqualTo('A');
                assertThat(users.get(5).charAt(56)).isEqualTo('U');
            });
        }

        @Test
        @DisplayName("a resource that does not resolve is reported, never seeded as an empty dataset")
        void anAbsentResourceIsReported() {
            FixtureInventory inventory = new FixtureInventory();
            FixtureDeclaration absent = new FixtureDeclaration("classpath:fixtures/no-such-file.txt", 1,
                    10, null, List.of("ACCTDAT"), null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> inventory.recordsOf(absent, LOADER, ASCII))
                    .withMessageContaining("no-such-file.txt")
                    .withMessageContaining("does not resolve");
        }

        @Test
        @DisplayName("a record count that disagrees with the file is reported")
        void aWrongRecordCountIsReported() {
            FixtureInventory inventory = new FixtureInventory();
            FixtureDeclaration wrong = new FixtureDeclaration("classpath:fixtures/trantype.txt", 8, 60,
                    null, List.of("TRANTYPE"), null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> inventory.recordsOf(wrong, LOADER, ASCII))
                    .withMessageContaining("holds 7 record(s) but declares 8");
        }

        @Test
        @DisplayName("a width that disagrees with the file is reported, naming the record")
        void aWrongWidthIsReported() {
            FixtureInventory inventory = new FixtureInventory();
            FixtureDeclaration wrong = new FixtureDeclaration("classpath:fixtures/trantype.txt", 7, 59,
                    null, List.of("TRANTYPE"), null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> inventory.recordsOf(wrong, LOADER, ASCII))
                    .withMessageContaining("Record 1")
                    .withMessageContaining("measures 60 byte(s) but the entry declares 59");
        }

        @Test
        @DisplayName("the code page is required rather than defaulted")
        void theCodePageIsRequired() {
            FixtureInventory inventory = new FixtureInventory();
            FixtureDeclaration declaration = new FixtureDeclaration("classpath:fixtures/trantype.txt", 7,
                    60, null, List.of("TRANTYPE"), null);

            assertThatNullPointerException()
                    .isThrownBy(() -> inventory.recordsOf(declaration, LOADER, null))
                    .withMessageContaining("code page");
        }
    }

    @Nested
    @DisplayName("The guards - each refuses the declaration it exists for")
    class TheGuards {

        @Test
        @DisplayName("a missing entry and an invented one are both reported")
        void aMissingOrInventedEntryIsReported() {
            FixtureInventory shortOfOne = shippedShape();
            shortOfOne.remove("usrsec");
            assertThatIllegalStateException().isThrownBy(() -> shortOfOne.validate(catalogue()))
                    .withMessageContaining("Missing: [usrsec]");

            FixtureInventory withAnExtra = shippedShape();
            withAnExtra.put("unused1y", declaration(null, 1, 10, null, List.of("ACCTDAT"),
                    List.of("0123456789")));
            assertThatIllegalStateException().isThrownBy(() -> withAnExtra.validate(catalogue()))
                    .withMessageContaining("Unexpected: [unused1y]");
        }

        @Test
        @DisplayName("an entry declaring both a resource and a seed is refused, and so is one with "
                + "neither")
        void anEntryMustBeEitherFileBackedOrInline() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> withAcctdata(declaration("classpath:fixtures/acctdata.txt", 50,
                            300, null, List.of("ACCTDAT"), List.of("x"))).validate(catalogue()))
                    .withMessageContaining("declares both");
            assertThatIllegalStateException()
                    .isThrownBy(() -> withAcctdata(declaration(null, 50, 300, null,
                            List.of("ACCTDAT"), null)).validate(catalogue()))
                    .withMessageContaining("declares neither");
        }

        @Test
        @DisplayName("a normalisation no wider than the measured width is refused")
        void aPadThatIsNotWiderIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> withAcctdata(declaration("classpath:fixtures/acctdata.txt", 50,
                            300, 300, List.of("ACCTDAT"), null)).validate(catalogue()))
                    .withMessageContaining("pad-to 300 against a measured width of 300");
        }

        @Test
        @DisplayName("a normalised width that is not the catalogue's record length is refused, naming "
                + "the DD")
        void aWidthThatIsNotTheCopybookWidthIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> withAcctdata(declaration("classpath:fixtures/acctdata.txt", 50,
                            299, null, List.of("ACCTDAT"), null)).validate(catalogue()))
                    .withMessageContaining("normalises to 299 bytes")
                    .withMessageContaining("ACCTDAT at 300");
        }

        @Test
        @DisplayName("a non-positive record count or width is refused, and so is an entry naming no DD")
        void degenerateDeclarationsAreRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> withAcctdata(declaration("classpath:fixtures/acctdata.txt", 0,
                            300, null, List.of("ACCTDAT"), null)).validate(catalogue()))
                    .withMessageContaining("declares 0 records");
            assertThatIllegalStateException()
                    .isThrownBy(() -> withAcctdata(declaration("classpath:fixtures/acctdata.txt", 50,
                            0, null, List.of("ACCTDAT"), null)).validate(catalogue()))
                    .withMessageContaining("0 bytes per record");
            assertThatIllegalStateException()
                    .isThrownBy(() -> withAcctdata(declaration("classpath:fixtures/acctdata.txt", 50,
                            300, null, List.of(), null)).validate(catalogue()))
                    .withMessageContaining("names no DD name");
            assertThatIllegalStateException()
                    .isThrownBy(() -> withAcctdata(null).validate(catalogue()))
                    .withMessageContaining("declares no properties at all");
        }

        @Test
        @DisplayName("the catalogue is required, because a pad is only meaningful against it")
        void theCatalogueIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> shippedShape().validate(null))
                    .withMessageContaining("dataset catalogue is required");
        }
    }

    @Nested
    @DisplayName("The seeding - the declared relations exist and hold the declared rows")
    class TheSeeding {

        @Test
        @DisplayName("every declared dataset is materialised, once per dataset and not once per DD name")
        void everyDeclaredDatasetIsMaterialised() {
            // The review's finding, answered directly: before this, a JVM on the test profile had the
            // bindings and no relations. Note the counts - cardxref serves FIVE DD names and is seeded
            // once, because five DD names address one dataset and an alternate index is a finder over its
            // base cluster rather than a second table (gate G45).
            boundInventory().run(context -> {
                FixtureSeeder seeder = context.getBean(FixtureSeeder.class);
                JdbcTemplate template = context.getBean(JdbcTemplate.class);

                Map<String, Integer> seeded = seeder.seedAll();

                assertThat(seeded.keySet())
                        .as("ten distinct datasets from ten declared entries across twenty DD names")
                        .containsExactlyInAnyOrderElementsOf(SEEDED_DATASETS);
                assertThat(seeded)
                        .containsEntry("CARDDEMO.TEST.ACCTDATA.VSAM.KSDS", 50)
                        .containsEntry("CARDDEMO.TEST.CARDXREF.VSAM.KSDS", 50)
                        .containsEntry("CARDDEMO.TEST.DALYTRAN", 300)
                        .containsEntry("CARDDEMO.TEST.DISCGRP", 51)
                        .containsEntry("CARDDEMO.TEST.TRANTYPE", 7)
                        .containsEntry("CARDDEMO.TEST.TRANCATG", 18)
                        .containsEntry("CARDDEMO.TEST.USRSEC.VSAM.KSDS", 10);
                for (Map.Entry<String, Integer> entry : seeded.entrySet()) {
                    assertThat(template.queryForObject(
                            "SELECT COUNT(*) FROM \"" + entry.getKey() + "\"", Integer.class))
                            .as("%s must actually hold its rows", entry.getKey())
                            .isEqualTo(entry.getValue());
                }
            });
        }

        @Test
        @DisplayName("the seeded rows are at the copybook width, with the normalisation applied")
        void theSeededRowsAreAtTheCopybookWidth() {
            boundInventory().run(context -> {
                context.getBean(FixtureSeeder.class).seedAll();
                JdbcTemplate template = context.getBean(JdbcTemplate.class);

                assertThat(template.queryForObject("SELECT " + FixtureSeeder.RECORD_IMAGE_COLUMN
                        + " FROM \"CARDDEMO.TEST.CARDXREF.VSAM.KSDS\" FETCH FIRST 1 ROW ONLY",
                        String.class))
                        .as("36 measured bytes, restored to the 50 CVACT03Y declares")
                        .hasSize(50);
                String user = template.queryForObject("SELECT " + FixtureSeeder.RECORD_IMAGE_COLUMN
                        + " FROM \"CARDDEMO.TEST.USRSEC.VSAM.KSDS\" WHERE "
                        + FixtureSeeder.RECORD_IMAGE_COLUMN + " LIKE 'ADMIN001%'", String.class);
                assertThat(user)
                        .as("57 in-stream bytes, restored to the 80 CSUSR01Y declares")
                        .hasSize(80);
                assertThat(user.substring(48, 56)).isEqualTo("PASSWORD");
            });
        }

        @Test
        @DisplayName("seeding twice leaves the declared rows and no duplicates")
        void seedingTwiceIsIdempotent() {
            // A hand-started JVM may be restarted against the same database, and a run that appended
            // would double every dataset - fifty accounts becoming a hundred, which no assertion and no
            // screen would report as wrong.
            boundInventory().run(context -> {
                FixtureSeeder seeder = context.getBean(FixtureSeeder.class);
                seeder.seedAll();
                seeder.seedAll();

                assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                        "SELECT COUNT(*) FROM \"CARDDEMO.TEST.ACCTDATA.VSAM.KSDS\"", Integer.class))
                        .isEqualTo(50);
            });
        }

        @Test
        @DisplayName("the seeder refuses every argument it cannot work without")
        void theSeederRefusesMissingCollaborators() {
            JdbcTemplate template = new JdbcTemplate();
            FixtureInventory inventory = shippedShape();
            org.springframework.transaction.PlatformTransactionManager manager =
                    new org.springframework.batch.support.transaction.ResourcelessTransactionManager();
            assertThatNullPointerException().isThrownBy(() -> new FixtureSeeder(null, catalogue(),
                    inventory, LOADER, ASCII, manager));
            assertThatNullPointerException().isThrownBy(() -> new FixtureSeeder(template, null,
                    inventory, LOADER, ASCII, manager));
            assertThatNullPointerException().isThrownBy(() -> new FixtureSeeder(template, catalogue(),
                    null, LOADER, ASCII, manager));
            assertThatNullPointerException().isThrownBy(() -> new FixtureSeeder(template, catalogue(),
                    inventory, null, ASCII, manager));
            assertThatNullPointerException().isThrownBy(() -> new FixtureSeeder(template, catalogue(),
                    inventory, LOADER, null, manager));
            assertThatNullPointerException().isThrownBy(() -> new FixtureSeeder(template, catalogue(),
                    inventory, LOADER, ASCII, null))
                    .withMessageContaining("auto-commit");
        }
    }

    @Nested
    @DisplayName("Nothing of this reaches the artifact")
    class NothingShips {

        @Test
        @DisplayName("the inventory, the seeder and the seeded entry point are all compiled from the "
                + "test tree")
        void everythingIsCompiledFromTheTestTree() {
            // The reason the declaring document is under src/test/resources in the first place: it
            // carries ten plaintext USRSEC rows, and a credential-shaped value inside a distributable
            // artifact is indistinguishable from a real one to a scanner (CWE-798, practice B6). The
            // code that reads it has to live on the same side of that line.
            for (Class<?> type : List.of(FixtureInventory.class, FixtureSeeder.class,
                    FixtureSeedingConfiguration.class, FixtureSeededApplication.class)) {
                assertThat(type.getProtectionDomain().getCodeSource().getLocation().toString())
                        .as("%s must be compiled from the test tree", type.getSimpleName())
                        .contains("test-classes");
            }
        }

        @Test
        @DisplayName("the declaring document is on the test classpath and not in the main resources")
        void theDeclaringDocumentIsATestResource() {
            assertThat(FixtureInventoryTest.class.getResource("/carddemo-test-fixtures.yml"))
                    .as("the import in application-test.yml is optional precisely so the packaged "
                            + "artifact - where this document is absent - has no DataSource and refuses "
                            + "to start")
                    .isNotNull();
            assertThat(FixtureInventoryTest.class.getResource("/carddemo-test-fixtures.yml").toString())
                    .contains("test-classes");
        }
    }

    // =================================================================================================
    // Fixtures.
    // =================================================================================================

    /** A catalogue holding the entries these guards address, at their copybook widths. */
    private static DatasetBindings catalogue() {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put("ACCTDAT", ksds("CARDDEMO.TEST.ACCTDATA.VSAM.KSDS", 300));
        bindings.put("ACCTFILE", ksds("CARDDEMO.TEST.ACCTDATA.VSAM.KSDS", 300));
        bindings.put("CARDDAT", ksds("CARDDEMO.TEST.CARDDATA.VSAM.KSDS", 150));
        bindings.put("CARDFILE", ksds("CARDDEMO.TEST.CARDDATA.VSAM.KSDS", 150));
        bindings.put("CARDAIX", ksds("CARDDEMO.TEST.CARDDATA.VSAM.KSDS", 150));
        bindings.put("CCXREF", ksds("CARDDEMO.TEST.CARDXREF.VSAM.KSDS", 50));
        bindings.put("CARDXREF", ksds("CARDDEMO.TEST.CARDXREF.VSAM.KSDS", 50));
        bindings.put("XREFFILE", ksds("CARDDEMO.TEST.CARDXREF.VSAM.KSDS", 50));
        bindings.put("CXACAIX", ksds("CARDDEMO.TEST.CARDXREF.VSAM.KSDS", 50));
        bindings.put("XREFFIL1", ksds("CARDDEMO.TEST.CARDXREF.VSAM.KSDS", 50));
        bindings.put("CUSTDAT", ksds("CARDDEMO.TEST.CUSTDATA.VSAM.KSDS", 500));
        bindings.put("CUSTFILE", ksds("CARDDEMO.TEST.CUSTDATA.VSAM.KSDS", 500));
        bindings.put("DALYTRAN", ksds("CARDDEMO.TEST.DALYTRAN", 350));
        bindings.put("DISCGRP", ksds("CARDDEMO.TEST.DISCGRP", 50));
        bindings.put("TCATBALF", ksds("CARDDEMO.TEST.TCATBAL.VSAM.KSDS", 50));
        bindings.put("TRANTYPE", ksds("CARDDEMO.TEST.TRANTYPE", 60));
        bindings.put("TRANCATG", ksds("CARDDEMO.TEST.TRANCATG", 60));
        bindings.put("USRSEC", ksds("CARDDEMO.TEST.USRSEC.VSAM.KSDS", 80));
        return bindings;
    }

    /**
     * A keyed binding at the given width.
     *
     * @param dsname the dataset name
     * @param width  the record length the copybook declares
     * @return the binding
     */
    private static DatasetBinding ksds(String dsname, int width) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, width, null, null, null, null,
                null);
    }

    /** The shipped inventory's shape, built by hand so a guard can be driven one field at a time. */
    private static FixtureInventory shippedShape() {
        FixtureInventory inventory = new FixtureInventory();
        inventory.put("acctdata", declaration("classpath:fixtures/acctdata.txt", 50, 300, null,
                List.of("ACCTDAT", "ACCTFILE"), null));
        inventory.put("carddata", declaration("classpath:fixtures/carddata.txt", 50, 150, null,
                List.of("CARDDAT", "CARDFILE", "CARDAIX"), null));
        inventory.put("cardxref", declaration("classpath:fixtures/cardxref.txt", 50, 36, 50,
                List.of("CCXREF", "CARDXREF", "XREFFILE", "CXACAIX", "XREFFIL1"), null));
        inventory.put("custdata", declaration("classpath:fixtures/custdata.txt", 50, 500, null,
                List.of("CUSTDAT", "CUSTFILE"), null));
        inventory.put("dailytran", declaration("classpath:fixtures/dailytran.txt", 300, 350, null,
                List.of("DALYTRAN"), null));
        inventory.put("discgrp", declaration("classpath:fixtures/discgrp.txt", 51, 50, null,
                List.of("DISCGRP"), null));
        inventory.put("tcatbal", declaration("classpath:fixtures/tcatbal.txt", 50, 50, null,
                List.of("TCATBALF"), null));
        inventory.put("trancatg", declaration("classpath:fixtures/trancatg.txt", 18, 60, null,
                List.of("TRANCATG"), null));
        inventory.put("trantype", declaration("classpath:fixtures/trantype.txt", 7, 60, null,
                List.of("TRANTYPE"), null));
        inventory.put("usrsec", declaration(null, 1, 57, 80, List.of("USRSEC"),
                List.of("ADMIN001MARGARET            GOLD                PASSWORDA")));
        return inventory;
    }

    /** The shipped shape with one {@code acctdata} entry replaced. */
    private static FixtureInventory withAcctdata(FixtureDeclaration declaration) {
        FixtureInventory inventory = shippedShape();
        inventory.put("acctdata", declaration);
        return inventory;
    }

    /**
     * One declaration, spelled out at the call site so each guard's input is visible.
     *
     * @param resource       the classpath location, or {@code null} for an inline entry
     * @param records        the declared record count
     * @param bytesPerRecord the declared measured width
     * @param padTo          the declared normalisation, or {@code null}
     * @param datasets       the DD names served
     * @param seed           inline records, or {@code null}
     * @return the declaration
     */
    private static FixtureDeclaration declaration(String resource, int records, int bytesPerRecord,
            Integer padTo, List<String> datasets, List<String> seed) {
        return new FixtureDeclaration(resource, records, bytesPerRecord, padTo, datasets, seed);
    }

    /**
     * The transaction manager the seeding boundary is opened on.
     *
     * <p>{@code DataSourceConfig} deliberately declares none - {@code BatchConfig} owns the module's
     * single manager - and this slice does not load {@code BatchConfig}, so it supplies one of its own
     * over the same datasource. In a full context (and in {@link FixtureSeededApplication}) the seeder
     * injects the module's own manager instead.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class TransactionBoundary {

        /**
         * A manager over the profile's own datasource.
         *
         * @param dataSource the profile's in-memory datasource
         * @return the manager
         */
        @org.springframework.context.annotation.Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager(
                javax.sql.DataSource dataSource) {
            return new org.springframework.jdbc.support.JdbcTransactionManager(dataSource);
        }
    }

    /**
     * The minimum configuration that enables the strict binding and nothing else, so the bind failure
     * an unknown key produces is the only thing that can fail the context.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(FixtureInventory.class)
    static class StrictBindingOnly {
    }
}
