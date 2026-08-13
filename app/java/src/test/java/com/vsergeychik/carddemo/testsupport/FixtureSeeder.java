package com.vsergeychik.carddemo.testsupport;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.testsupport.FixtureInventory.FixtureDeclaration;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Materialises the {@code test} profile's declared fixture inventory into the profile's own backend.
 *
 * <h2>What it is for</h2>
 * <p>The profile rebinds all twenty-seven DD names onto an in-memory database, and
 * {@link FixtureInventory} declares which fixture holds each one's rows. Nothing turned that
 * declaration into data: a JVM started on the profile had the bindings and no relations, so a repository
 * reached a dataset that did not exist. This class closes that - it creates one relation per distinct
 * dataset name and inserts the declared records, right-padded once to the copybook width by
 * {@link FixtureInventory#recordsOf(FixtureDeclaration, ResourceLoader, Charset)}.
 *
 * <h2>What it deliberately is not</h2>
 * <p>It is <strong>not</strong> the parity harness's seeding, and it does not replace it. A parity case
 * declares the datasets and rows that case needs and holds them privately for its duration, which is
 * what lets one case seed an expired card, another an empty dataset and another a row narrower than its
 * copybook; a shared pre-seeded database would make those cases unreachable. This seeder answers the
 * other question - what a JVM started on this profile by hand should find - and it is opted into
 * explicitly ({@link FixtureSeedingConfiguration}) rather than applied to every context, so no test's
 * outcome depends on data it did not declare.
 *
 * <h2>The DDL, and why it is not a gate G44 breach</h2>
 * <p>Gate G44 forbids data-definition statements in the <em>delivered module</em>, and nothing under
 * {@code src/main} issues one: a deployment is required to provide a relation for every configured
 * dataset (deployment obligation 2), and the module refuses rather than creating. This class is under
 * {@code src/test}, is never packaged, and creates throwaway relations in an in-memory database that
 * exists for the life of one JVM - it stands in for that deployment obligation exactly as the rest of
 * the suite's {@code CREATE TABLE} scaffolding does, and it can reach nothing else, because the profile
 * binds every dataset to a {@code CARDDEMO.TEST.*} name.
 *
 * <h2>Encoding</h2>
 * <p>The code page is injected, never defaulted. Under this profile it is {@code US-ASCII}, because the
 * fixtures are the ASCII text files derived from {@code app/data/ASCII}; decoding them in the platform
 * default is the one thing this module never does anywhere.
 */
public final class FixtureSeeder {

    /** Where a seeding summary goes. */
    private static final Logger LOG = LoggerFactory.getLogger(FixtureSeeder.class);

    /**
     * The record-image column every relation this class creates presents.
     *
     * <p>The name is this seeder's choice and nothing depends on it: {@link DatasetRelation} discovers
     * the column from {@link java.sql.ResultSetMetaData} at ordinal
     * {@value DatasetRelation#RECORD_IMAGE_COLUMN_INDEX}, so the position is the contract and the name is
     * site-specific. It matches the name the rest of the suite's scaffolding uses, and the one a
     * deployment's own provisioning script would most plainly write.
     */
    public static final String RECORD_IMAGE_COLUMN = "RECORD_IMAGE";

    /** The backend the profile bound. */
    private final JdbcTemplate jdbcTemplate;

    /** The shipped catalogue, which resolves a DD name to the dataset it addresses. */
    private final DatasetBindings catalogue;

    /** The declared inventory. */
    private final FixtureInventory inventory;

    /** Resolves each declared {@code classpath:} fixture. */
    private final ResourceLoader resourceLoader;

    /** The code page the fixtures are stored in. */
    private final Charset datasetCharset;

    /**
     * The boundary every statement this class issues is applied through.
     *
     * <p>Not optional, and not tidiness. The pool hands out connections with {@code auto-commit}
     * disabled, so an insert issued with nothing bound to the thread executes, reports the row it
     * affected, and is rolled back when the connection is returned - which is precisely how this seeder
     * first behaved: it logged 636 records and every relation was then empty. One boundary around the
     * whole inventory also makes the seeding all-or-nothing, so a fixture that fails to resolve half way
     * through cannot leave a partially seeded profile behind.
     */
    private final TransactionTemplate boundary;

    /**
     * Builds a seeder over one backend and one inventory.
     *
     * @param jdbcTemplate   the profile's backend
     * @param catalogue      the shipped dataset catalogue
     * @param inventory      the declared fixture inventory
     * @param resourceLoader resolves the declared fixture resources
     * @param datasetCharset the code page the fixtures are stored in, never the platform default
     * @param transactionManager the manager the seeding boundary is opened on
     */
    public FixtureSeeder(JdbcTemplate jdbcTemplate, DatasetBindings catalogue,
                         FixtureInventory inventory, ResourceLoader resourceLoader,
                         Charset datasetCharset, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A backend is required to seed into");
        this.catalogue = Objects.requireNonNull(catalogue, "The dataset catalogue is required: it is "
                + "what resolves a DD name to the dataset it addresses, and several DD names address "
                + "one dataset");
        this.inventory = Objects.requireNonNull(inventory, "The declared inventory is required");
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "A resource loader is required to "
                + "resolve the declared fixture resources");
        this.datasetCharset = Objects.requireNonNull(datasetCharset, "The code page is required: a "
                + "fixture is bytes, and this module never decodes bytes in the platform default");
        this.boundary = new TransactionTemplate(Objects.requireNonNull(transactionManager,
                "A transaction manager is required: the pool disables auto-commit, so seeding with no "
                + "boundary inserts every record, reports it, and has it rolled back when the "
                + "connection returns"));
        this.boundary.afterPropertiesSet();
    }

    /**
     * Validates the inventory, then materialises every entry it declares.
     *
     * <p>Validation first and unconditionally: seeding a declaration that disagrees with the fixtures or
     * with the catalogue would produce data no assertion could trust. Then, per entry, one relation per
     * distinct dataset name - created if absent, emptied if present, and filled with the declared
     * records at the copybook width.
     *
     * @return the number of records seeded, by dataset name, in declaration order
     * @throws IllegalStateException if the inventory is incoherent, a fixture does not resolve, or a
     *                               fixture's geometry differs from what it declares
     */
    public Map<String, Integer> seedAll() {
        inventory.validate(catalogue);
        Map<String, Integer> seeded = new LinkedHashMap<>();
        boundary.executeWithoutResult(status -> inventory.forEach((name, declaration) -> {
            List<String> records = inventory.recordsOf(declaration, resourceLoader, datasetCharset);
            for (String datasetName : inventory.datasetNamesOf(declaration, catalogue)) {
                seedRelation(datasetName, declaration.normalisedWidth(), records);
                seeded.put(datasetName, records.size());
            }
        }));
        LOG.info("Seeded the test profile's declared fixture inventory: {} dataset(s), {} record(s) - "
                + "{}. This is the profile's declared data, not a parity case's: a case still declares "
                + "and holds its own rows.", seeded.size(),
                seeded.values().stream().mapToInt(Integer::intValue).sum(), seeded);
        return Map.copyOf(seeded);
    }

    /**
     * Creates or empties one relation and inserts the given records into it.
     *
     * @param datasetName the dataset name, validated as a z/OS dataset name before it is delimited
     * @param width       the record width the column holds
     * @param records     the records to insert, in order
     */
    private void seedRelation(String datasetName, int width, List<String> records) {
        DatasetRelation relation = DatasetRelation.of(datasetName, width);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + relation.identifier() + " ("
                + RECORD_IMAGE_COLUMN + " CHAR(" + width + "))");
        jdbcTemplate.update(relation.deleteAll());
        List<Object[]> rows = new ArrayList<>(records.size());
        for (String record : records) {
            rows.add(new Object[] { record });
        }
        jdbcTemplate.batchUpdate(relation.insertRecordImage(RECORD_IMAGE_COLUMN), rows);
    }
}
